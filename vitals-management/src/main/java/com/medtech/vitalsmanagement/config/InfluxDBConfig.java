package com.medtech.vitalsmanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

@Configuration
public class InfluxDBConfig {

    @Value("${influxdb.url:http://localhost:8086}")
    private String url;//Adresse du serveur InfluxDB

    @Value("${influxdb.token:}")
    private String token;//Jeton d'authentification pour InfluxDB

    @Value("${influxdb.org:medtech}")
    private String org;//Organisation dans InfluxDB

    @Value("${influxdb.bucket:vitals}")
    private String bucket;//Bucket de stockage des données dans InfluxDB

    @Bean
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
    }
}