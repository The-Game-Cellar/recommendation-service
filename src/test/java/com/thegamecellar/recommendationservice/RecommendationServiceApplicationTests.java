package com.thegamecellar.recommendationservice;

import com.thegamecellar.recommendationservice.support.RecommendationServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

// The context starts on Postgres with Flyway on and ddl-auto=validate, so it only comes up when the
// migrations build exactly the schema the entities expect.
class RecommendationServiceApplicationTests extends RecommendationServiceTestBase {

    @Test
    void everyMigrationRanAndTheEntitiesValidateAgainstTheSchema() throws Exception {
        int files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql").length;

        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);

        assertThat(files).isPositive();
        assertThat(applied).isEqualTo(files);
    }
}
