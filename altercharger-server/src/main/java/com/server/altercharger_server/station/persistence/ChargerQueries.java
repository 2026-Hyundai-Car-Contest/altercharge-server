package com.server.altercharger_server.station.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface ChargerQueries {
    List<ChargerEntity> findByIds(Collection<ChargerId> ids);

    int markDeletedForRegions(Collection<String> zcodes);

    int refreshStatusObservedAtForRegions(Collection<String> zcodes, OffsetDateTime observedAt);
}
