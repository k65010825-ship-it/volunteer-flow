CREATE TABLE schema_metadata (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schema_version VARCHAR(50) NOT NULL,
    applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_schema_metadata_version (schema_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO schema_metadata (schema_version) VALUES ('stage0-scaffold');
