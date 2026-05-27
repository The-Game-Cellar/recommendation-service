package com.thegamecellar.recommendationservice.model.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PoolHoldingId implements Serializable {
    private String userId;
    private String genre;
    private Integer igdbId;
}
