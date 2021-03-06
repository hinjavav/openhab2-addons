/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.chromecast.internal;

import static org.eclipse.smarthome.core.types.UnDefType.UNDEF;
import static org.openhab.binding.chromecast.internal.ChromecastBindingConstants.*;
import static su.litvak.chromecast.api.v2.MediaStatus.PlayerState.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.PlayPauseType;
import org.eclipse.smarthome.core.library.types.PointType;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.io.net.http.HttpUtil;
import org.openhab.binding.chromecast.internal.handler.ChromecastHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.litvak.chromecast.api.v2.Application;
import su.litvak.chromecast.api.v2.Media;
import su.litvak.chromecast.api.v2.MediaStatus;
import su.litvak.chromecast.api.v2.Status;
import su.litvak.chromecast.api.v2.Volume;

/**
 * Responsible for updating the Thing status based on messages received from a ChromeCast. This doesn't query
 * anything - it just parses the messages and updates the Thing. Message handling/scheduling/receiving is done
 * elsewhere.
 * <p>
 * This also maintains state of both volume and the appSessionId (only if we started playing media).
 *
 * @author Jason Holmes - Initial contribution
 */
public class ChromecastStatusUpdater {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Thing thing;
    private final ChromecastHandler callback;

    private String appSessionId;
    private PercentType volume;
    private String imageSrc;

    public ChromecastStatusUpdater(Thing thing, ChromecastHandler callback) {
        this.thing = thing;
        this.callback = callback;
    }

    public PercentType getVolume() {
        return volume;
    }

    public String getAppSessionId() {
        return appSessionId;
    }

    public void setAppSessionId(String appSessionId) {
        this.appSessionId = appSessionId;
    }

    public void processStatusUpdate(final Status status) {
        if (status == null) {
            updateStatus(ThingStatus.OFFLINE);
            updateAppStatus(null);
            updateVolumeStatus(null);
            return;
        }

        if (status.applications == null) {
            this.appSessionId = null;
        }

        updateStatus(ThingStatus.ONLINE);
        updateAppStatus(status.getRunningApp());
        updateVolumeStatus(status.volume);
    }

    public void updateAppStatus(final Application application) {
        State name = UNDEF;
        State id = UNDEF;
        State statusText = UNDEF;
        OnOffType idling = OnOffType.ON;

        if (application != null) {
            name = new StringType(application.name);
            id = new StringType(application.id);
            statusText = new StringType(application.statusText);
            idling = application.isIdleScreen ? OnOffType.ON : OnOffType.OFF;
        }

        callback.updateState(CHANNEL_APP_NAME, name);
        callback.updateState(CHANNEL_APP_ID, id);
        callback.updateState(CHANNEL_STATUS_TEXT, statusText);
        callback.updateState(CHANNEL_IDLING, idling);
    }

    public void updateVolumeStatus(final Volume volume) {
        if (volume == null) {
            return;
        }

        PercentType value = new PercentType((int) (volume.level * 100));
        this.volume = value;

        callback.updateState(CHANNEL_VOLUME, value);
        callback.updateState(CHANNEL_MUTE, volume.muted ? OnOffType.ON : OnOffType.OFF);
    }

    public void updateMediaStatus(final MediaStatus mediaStatus) {
        logger.debug("MEDIA_STATUS {}", mediaStatus);

        // In-between songs? It's thinking? It's not doing anything
        if (mediaStatus == null) {
            callback.updateState(CHANNEL_CURRENT_TIME, UNDEF);
            updateMediaInfoStatus(null);
            return;
        }

        switch (mediaStatus.playerState) {
            case IDLE:
                break;
            case PAUSED:
                callback.updateState(CHANNEL_CONTROL, PlayPauseType.PAUSE);
                break;
            case BUFFERING:
            case PLAYING:
                callback.updateState(CHANNEL_CONTROL, PlayPauseType.PLAY);
                break;
            default:
                logger.debug("Unknown mediaStatus: {}", mediaStatus.playerState);
                break;
        }

        callback.updateState(CHANNEL_CURRENT_TIME, new QuantityType<>(mediaStatus.currentTime, SmartHomeUnits.SECOND));

        // If we're playing, paused or buffering but don't have any MEDIA information don't null everything out.
        Media media = mediaStatus.media;
        if (media == null && (mediaStatus.playerState == PLAYING || mediaStatus.playerState == PAUSED
                || mediaStatus.playerState == BUFFERING)) {
            return;
        }

        updateMediaInfoStatus(media);
    }

    private void updateMediaInfoStatus(final Media media) {
        State duration = UNDEF;
        String metadataType = Media.MetadataType.GENERIC.name();
        if (media != null) {
            metadataType = media.getMetadataType().name();

            // duration can be null when a new song is about to play.
            if (media.duration != null) {
                duration = new QuantityType<>(media.duration, SmartHomeUnits.SECOND);
            }
        }

        callback.updateState(CHANNEL_DURATION, duration);
        callback.updateState(CHANNEL_METADATA_TYPE, new StringType(metadataType));

        updateMetadataStatus(media == null || media.metadata == null ? Collections.emptyMap() : media.metadata);
    }

    private void updateMetadataStatus(Map<String, Object> metadata) {
        updateLocation(metadata);
        updateImage(metadata);

        thing.getChannels().stream() //
                .map(channel -> channel.getUID())
                .filter(channelUID -> METADATA_SIMPLE_CHANNELS.contains(channelUID.getId()))
                .forEach(channelUID -> updateChannel(channelUID, metadata));
    }

    /** Lat/lon are combined into 1 channel so we have to handle them as a special case. */
    private void updateLocation(Map<String, Object> metadata) {
        if (!callback.isLinked(CHANNEL_LOCATION)) {
            callback.updateState(CHANNEL_LOCATION, UNDEF);
            return;
        }

        Double lat = (Double) metadata.get(LOCATION_METADATA_LATITUDE);
        Double lon = (Double) metadata.get(LOCATION_METADATA_LONGITUDE);

        if (lat == null || lon == null) {
            callback.updateState(CHANNEL_LOCATION, UNDEF);
        } else {
            PointType pointType = new PointType(new DecimalType(lat), new DecimalType(lon));
            callback.updateState(CHANNEL_LOCATION, pointType);
        }
    }

    private void updateImage(Map<String, Object> metadata) {
        // Channel name and metadata key don't match.
        Object imagesValue = metadata.get("images");

        if (!(callback.isLinked(CHANNEL_IMAGE) || (callback.isLinked(CHANNEL_IMAGE_SRC)))) {
            return;
        }

        if (imagesValue == null) {
            callback.updateState(CHANNEL_IMAGE_SRC, UNDEF);
            return;
        }

        String imageSrc = null;

        @SuppressWarnings("unchecked")
        List<Map<String, String>> strings = (List<Map<String, String>>) imagesValue;
        for (Map<String, String> stringMap : strings) {
            String url = stringMap.get("url");
            if (url != null) {
                imageSrc = url;
                break;
            }
        }

        // Poor man's cache. If the imageSrc is the same, don't update them.
        if (Objects.equals(this.imageSrc, imageSrc)) {
            return;
        } else {
            this.imageSrc = imageSrc;
        }

        if (callback.isLinked(CHANNEL_IMAGE_SRC)) {
            callback.updateState(CHANNEL_IMAGE_SRC, imageSrc == null ? UNDEF : new StringType(imageSrc));
        }

        if (callback.isLinked(CHANNEL_IMAGE)) {
            callback.updateState(CHANNEL_IMAGE, imageSrc == null ? UNDEF : HttpUtil.downloadImage(imageSrc));
        }
    }

    private void updateChannel(ChannelUID channelUID, Map<String, Object> metadata) {
        if (!callback.isLinked(channelUID)) {
            return;
        }

        Object value = getValue(channelUID.getId(), metadata);
        State state;

        if (value == null) {
            state = UNDEF;
        } else if (value instanceof Double) {
            state = new DecimalType((Double) value);
        } else if (value instanceof Integer) {
            state = new DecimalType(((Integer) value).longValue());
        } else if (value instanceof String) {
            state = new StringType(value.toString());
        } else if (value instanceof ZonedDateTime) {
            state = new DateTimeType((ZonedDateTime) value);
        } else {
            state = UNDEF;
            logger.warn("Update channel {}: Unsupported value type {}", channelUID, value.getClass().getSimpleName());
        }

        callback.updateState(channelUID, state);
    }

    private Object getValue(String channelId, Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }

        if (CHANNEL_BROADCAST_DATE.equals(channelId) || CHANNEL_RELEASE_DATE.equals(channelId)
                || CHANNEL_CREATION_DATE.equals(channelId)) {
            String dateString = (String) metadata.get(channelId);
            return (dateString == null) ? null
                    : ZonedDateTime.ofInstant(Instant.parse(dateString), ZoneId.systemDefault());
        }

        return metadata.get(channelId);
    }

    public void updateStatus(ThingStatus status) {
        this.updateStatus(status, ThingStatusDetail.NONE, null);
    }

    public void updateStatus(ThingStatus status, ThingStatusDetail statusDetail, String description) {
        callback.updateStatus(status, statusDetail, description);
    }
}
