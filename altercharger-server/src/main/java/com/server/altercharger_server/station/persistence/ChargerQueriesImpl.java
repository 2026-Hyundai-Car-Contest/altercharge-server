package com.server.altercharger_server.station.persistence;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import static com.server.altercharger_server.station.persistence.QChargerEntity.chargerEntity;
import static com.server.altercharger_server.station.persistence.QStationEntity.stationEntity;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChargerQueriesImpl implements ChargerQueries {
    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    @Override
    public List<ChargerEntity> findByIds(Collection<ChargerId> ids) {
        return queryFactory.selectFrom(chargerEntity)
                .join(chargerEntity.station, stationEntity).fetchJoin()
                .where(chargerEntity.id.in(ids))
                .fetch();
    }

    @Override
    @Transactional
    public int markDeletedForRegions(Collection<String> zcodes) {
        return executeBulk(queryFactory.update(chargerEntity)
                .set(chargerEntity.deleted, true)
                .where(inRegions(zcodes)));
    }

    @Override
    @Transactional
    public int refreshStatusObservedAtForRegions(Collection<String> zcodes, OffsetDateTime observedAt) {
        return executeBulk(queryFactory.update(chargerEntity)
                .set(chargerEntity.statusObservedAt, observedAt)
                .where(chargerEntity.deleted.isFalse(), inRegions(zcodes)));
    }

    private BooleanExpression inRegions(Collection<String> zcodes) {
        // Use the mapped ID column so a bulk UPDATE never needs an implicit association join.
        return chargerEntity.id.stationId.in(JPAExpressions.select(stationEntity.stationId)
                .from(stationEntity).where(stationEntity.zcode.in(zcodes)));
    }

    private int executeBulk(JPAUpdateClause update) {
        // Match @Modifying(flushAutomatically = true, clearAutomatically = true): pending deltas
        // must reach the DB first and managed rows must not retain clocks from before the update.
        entityManager.flush();
        long affected = update.execute();
        entityManager.clear();
        return Math.toIntExact(affected);
    }
}
