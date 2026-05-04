package com.medtech.ingestion.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "cloudflare.r2")
public class CloudflareR2Properties {

    private boolean enabled = false;

    private String accountId;

    private String accessKeyId;

    private String secretAccessKey;

    private String bucketName;

    private String endpoint;

    private String region = "auto";

    private String objectPrefix = "snapshots";
}