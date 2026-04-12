package com.thegamecellar.recommendationservice.model.dto.library;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserPlatformDTO {
    private Long id;
    private String platformName;
    private Boolean isPrimary;
}
