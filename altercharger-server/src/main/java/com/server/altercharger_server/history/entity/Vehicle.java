package com.server.altercharger_server.history.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "vehicles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Vehicle {
    @Id
    @Column(name = "vehicle_id", length = 100)
    private String vehicleId;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;
}
