package com.server.altercharger_server.station.persistence;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import static com.server.altercharger_server.station.persistence.QChargerEntity.chargerEntity;
import static com.server.altercharger_server.station.persistence.QStationEntity.stationEntity;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationQueriesImpl implements StationQueries {
    private static final QChargerEntity activeCharger = new QChargerEntity("activeCharger");
    private final JPAQueryFactory queryFactory;

    @Override
    public List<StationEntity> findActiveWithChargers() {
        return withChargers().where(hasActiveCharger()).orderBy(stationEntity.stationId.asc()).fetch();
    }

    @Override
    public List<StationEntity> findActiveWithinBounds(double south, double north, double west, double east,
                                                     boolean wrapsLongitude) {
        BooleanExpression longitude = wrapsLongitude
                ? stationEntity.lng.goe(west).or(stationEntity.lng.loe(east))
                : stationEntity.lng.between(west, east);
        return withChargers()
                .where(stationEntity.lat.between(south, north), longitude, hasActiveCharger())
                .orderBy(stationEntity.stationId.asc())
                .fetch();
    }

    @Override
    public Optional<StationEntity> findWithChargers(String stationId) {
        return Optional.ofNullable(withChargers().where(stationEntity.stationId.eq(stationId)).fetchOne());
    }

    private JPAQuery<StationEntity> withChargers() {
        // Keep the managed collection complete; deleted rows are filtered only during DTO mapping.
        // The caller applies exact distance, sorting and pagination after conservative DB bounds.
        return queryFactory.selectFrom(stationEntity).distinct()
                .leftJoin(stationEntity.chargers, chargerEntity).fetchJoin();
    }

    private BooleanExpression hasActiveCharger() {
        return JPAExpressions.selectOne().from(activeCharger)
                .where(activeCharger.station.eq(stationEntity), activeCharger.deleted.isFalse()).exists();
    }
}
