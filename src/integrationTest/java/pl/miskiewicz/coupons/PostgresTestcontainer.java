package pl.miskiewicz.coupons;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("public.ecr.aws/docker/library/postgres:18-alpine")
            .asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
