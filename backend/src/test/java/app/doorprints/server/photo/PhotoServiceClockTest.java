/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package app.doorprints.server.photo;

import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The photo times come from the injected clock (S4b-BL-166): the time a photo is stored and the time of its
 * tombstone are the fixed instant, not the system clock.
 */
class PhotoServiceClockTest {

    private static final Instant FIXED = Instant.parse("2026-03-05T04:30:00Z");

    private final PhotoRepository photos = mock(PhotoRepository.class);
    private final HouseRepository houses = mock(HouseRepository.class);
    private final SyncVersions versions = mock(SyncVersions.class);
    private final PhotoService service = new PhotoService(photos, houses, versions,
            new ClientClock(Clock.fixed(FIXED, ZoneOffset.UTC), 300, 365),
            new AppProperties(null, null, null, null, null, null, null, null, null));

    private static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void aNewPhotoIsStoredWithTheClocksTime() throws Exception {
        var houseId = UUID.randomUUID();
        when(houses.findById(houseId)).thenReturn(Optional.of(new House(houseId)));
        when(versions.next()).thenReturn(7L);

        service.upload(houseId, null, png());

        var saved = ArgumentCaptor.forClass(Photo.class);
        verify(photos).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(FIXED);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(FIXED);
    }

    @Test
    void aDeletedPhotoIsTombstonedWithTheClocksTime() throws Exception {
        var photo = new Photo(UUID.randomUUID(), UUID.randomUUID(), "image/png", png(),
                Instant.parse("2020-01-01T00:00:00Z"), 1);
        when(photos.findById(any())).thenReturn(Optional.of(photo));
        when(versions.next()).thenReturn(8L);

        service.delete(photo.getId());

        assertThat(photo.isDeleted()).isTrue();
        assertThat(photo.getUpdatedAt()).isEqualTo(FIXED);
        assertThat(photo.getSyncVersion()).isEqualTo(8L);
    }
}
