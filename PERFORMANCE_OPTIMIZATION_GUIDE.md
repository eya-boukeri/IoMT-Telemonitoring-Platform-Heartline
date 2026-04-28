# Performance Optimization & Scaling Guide

## Executive Summary

Based on load testing results, platformeIOT can sustain **50 concurrent patients per instance** with excellent performance. To support higher patient counts, **horizontal scaling** with load balancing is recommended.

---

## 1. Immediate Optimizations (Quick Wins)

### 1.1 Reduce Docker Overhead in Simulations
**Current Issue:** PowerShell exec into Docker for each MQTT message  
**Impact:** Creates 100+ simultaneous Docker socket connections at high loads  
**Optimization:** Use native Docker SDK or pre-built MQTT publishing tool

```powershell
# Current (Inefficient):
Get-Content "temp_msg.json" | docker exec -i mosquitto-pfa-v2 mosquitto_pub ...

# Recommended (Optimized):
$config = @{
    host = "localhost"
    port = 1883
    topic = "vitals/$patientId/data"
}
# Use .NET MQTT client library (e.g., MQTTnet) for native integration
```

**Expected Improvement:** 30-40% reduction in Docker API overhead

### 1.2 Increase MQTT Broker Connection Limits
**File:** `pfa-infrastructure/mosquitto/mosquitto.conf`

```conf
# Current defaults (often 100 connections)
# Add/modify:
max_connections -1          # Unlimited connections
listener 1883
max_queued_messages 1000    # Increase queue
message_size_limit 0         # Remove message size limit
```

**Expected Improvement:** Handle 200+ concurrent clients per broker instance

### 1.3 Enable Kafka Batching
**File:** `ingestion-service/src/main/resources/application.yml`

```yaml
kafka:
  producer:
    batch-size: 16384                    # 16KB batches
    linger-ms: 10                        # Wait max 10ms for batch
    acks: 1                              # Faster than acks=all
    compression-type: snappy             # Reduce network overhead
```

**Expected Improvement:** 2-3x throughput increase with same CPU

### 1.4 Optimize PostgreSQL for High Insert Rate
**File:** `pfa-infrastructure/postgres/postgresql.conf`

```sql
-- Connection pooling
max_connections = 200
shared_buffers = 256MB                  -- 25% of available RAM
effective_cache_size = 1GB
work_mem = 4MB

-- Write optimization
wal_buffers = 16MB
checkpoint_timeout = 15min
checkpoint_completion_target = 0.9
```

**Expected Improvement:** 50% faster data persistence, reduced I/O bottlenecks

---

## 2. Medium-Term Improvements (2-4 weeks)

### 2.1 Implement Connection Pooling for Docker
**Replace:** Shell-based docker exec calls  
**With:** Docker SDK with connection pooling

```java
// Example: Docker Java client with pool
PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
connManager.setMaxTotal(100);              // Max total connections
connManager.setDefaultMaxPerRoute(20);     // Max per host

DockerClient docker = DockerClientBuilder.getInstance()
    .withDockerHost("unix:///var/run/docker.sock")
    .withHttpClient(httpClient)
    .build();
```

**Expected Improvement:** Eliminate Docker API timeouts at high concurrency

### 2.2 Implement MQTT Client Connection Pooling
**Replace:** Individual MQTT connections per job  
**With:** Shared connection pool

```java
@Bean
public MqttConnectOptions mqttConnectOptions() {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setCleanSession(false);
    options.setAutomaticReconnect(true);
    options.setMaxInflight(1000);          // Pending messages
    return options;
}

// Reuse single connection for all publishers
public class MqttPublisherPool {
    private static final MqttAsyncClient client;
    
    public void publish(String topic, String message) {
        client.publish(topic, message.getBytes(), 1, false);
    }
}
```

**Expected Improvement:** 5-10x reduction in connection overhead

### 2.3 Add Caching Layer for Anomaly Detection
**Problem:** Recalculating anomaly models for each patient  
**Solution:** Cache model predictions with TTL

```java
@Cacheable(value = "anomalyCache", key = "#patientId", unless = "#result == null")
public AnomalyResult detectAnomaly(String patientId, VitalSigns vitals) {
    return anomalyService.predict(vitals);
}

// Cache config:
// cacheName: anomalyCache
// ttl: 5 minutes
```

**Expected Improvement:** 30-50% CPU reduction, faster response times

---

## 3. Long-Term Scaling Strategy (1-3 months)

### 3.1 Horizontal Scaling Architecture

```
┌─────────────────────────────────────┐
│        MQTT Topic Partitioning      │
│  vitals/{1..N}/data (N = instances) │
└──────────┬──────────────────────────┘
           │
    ┌──────┴──────┐
    │             │
┌───▼──┐     ┌───▼──┐
│ Inst1│ ... │InstN  │
│      │     │       │
│ 50px │     │ 50px  │
└──────┘     └──────┘
    │             │
    └──────┬──────┘
           │
    ┌──────▼──────┐
    │  Kafka      │
    │  (Topics)   │
    └──────┬──────┘
           │
    ┌──────▼──────┐
    │ PostgreSQL  │
    │ (Replica)   │
    └─────────────┘
```

**Implementation:**
1. Deploy N ingestion-service instances (each handles 50 patients)
2. Partition MQTT topics: `vitals/{1..N}/data`
3. Each instance subscribes to dedicated topic
4. All instances write to shared PostgreSQL
5. Use Docker Swarm or Kubernetes for orchestration

**Expected Scale:** 1000+ concurrent patients

### 3.2 Kubernetes Deployment with Auto-Scaling

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ingestion-service
spec:
  replicas: 3  # Start with 3, scale as needed
  selector:
    matchLabels:
      app: ingestion-service
  template:
    spec:
      containers:
      - name: ingestion-service
        image: ingestion-service:latest
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1Gi
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ingestion-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ingestion-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70    # Scale up when CPU > 70%
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80    # Scale up when memory > 80%
```

**Expected Benefits:**
- Automatic scaling based on patient load
- No manual intervention required
- Cost optimization (scale down during off-peak)

### 3.3 Multi-Region Deployment

For geographic distribution and disaster recovery:

```
Region 1 (US East)          Region 2 (EU West)
┌──────────────────┐       ┌──────────────────┐
│ Ingestion x10    │       │ Ingestion x8     │
│ PostgreSQL (m)   │◄─────►│ PostgreSQL (s)   │
│ Redis Cache      │       │ Redis Cache      │
└──────────────────┘       └──────────────────┘
        ▲                           ▲
        │                           │
   Patients                    Patients
   (US)                       (EU)
```

**Implementation:**
- Replicate PostgreSQL with logical replication
- Use Redis for session state (geo-redundant)
- Route patients to nearest regional instance
- Cross-region monitoring

---

## 4. Performance Tuning Parameters

### 4.1 Recommended Configuration for 50-Patient Instance

```properties
# Ingestion Service
server.tomcat.threads.max=50
server.tomcat.accept-count=100
server.tomcat.max-connections=200

# MQTT Configuration
mqtt.max-connections=200
mqtt.message-queue-size=10000
mqtt.keep-alive=60

# Kafka Configuration
kafka.producer.batch-size=16384
kafka.producer.linger-ms=10
kafka.consumer.fetch-min-bytes=1024

# Database Configuration
hikari.maximum-pool-size=20
hikari.minimum-idle=5
hikari.connection-timeout=20000

# Anomaly Detection
anomaly.cache-ttl=300
anomaly.batch-size=100
anomaly.batch-timeout=5000
```

### 4.2 JVM Tuning for Ingestion Service

```bash
# JVM flags in docker-compose
JAVA_OPTS: "-Xmx512m -Xms512m \
            -XX:+UseG1GC \
            -XX:MaxGCPauseMillis=200 \
            -XX:+PrintGCDetails \
            -XX:+PrintGCDateStamps"
```

---

## 5. Monitoring & Alerting

### 5.1 Key Metrics to Monitor

```yaml
Metrics:
  - ingestion_messages_per_second    (target: >8 at 50px)
  - ingestion_message_latency_ms     (target: <500ms p95)
  - mqtt_connection_count            (alert: >180)
  - kafka_producer_lag               (alert: >1000)
  - postgresql_connections           (alert: >150)
  - docker_exec_errors               (alert: >0)
  - cpu_usage_percent                (warn: >70%, critical: >90%)
  - memory_usage_percent             (warn: >75%, critical: >90%)
```

### 5.2 Alert Rules

```yaml
alerts:
  - name: HighMessageLatency
    condition: ingestion_message_latency_p95 > 500
    action: scale-up

  - name: DockerAPIErrors
    condition: docker_exec_errors > 0
    action: page-oncall

  - name: MQTTConnectionLimit
    condition: mqtt_connections > 180
    action: increase-broker-capacity

  - name: CPUThrottling
    condition: cpu_usage > 90
    action: scale-up / investigate
```

---

## 6. Implementation Roadmap

| Phase | Timeline | Tasks | Expected Impact |
|-------|----------|-------|-----------------|
| **Phase 1** | Week 1 | MQTT broker optimization, Kafka batching | 20-30% throughput improvement |
| **Phase 2** | Week 2-3 | Connection pooling, Caching layer | 30-50% CPU reduction |
| **Phase 3** | Week 4-6 | Kubernetes setup, Auto-scaling | Support 500+ patients |
| **Phase 4** | Week 7-12 | Multi-region deployment | Global redundancy |

---

## 7. Capacity Planning

### Current Capacity
- **Single Instance:** 50 concurrent patients
- **Throughput:** 8.8 messages/second
- **Resource Usage:** 7.88% CPU, 331 MiB memory

### Projected Capacity (After Optimizations)
- **Single Instance:** 100-150 concurrent patients (2-3x improvement)
- **Throughput:** 20-25 messages/second
- **Resource Usage:** ~60% CPU, 400 MiB memory

### Scaling Formula
```
Total Patients Supported = (Instances × Optimized Capacity) - Overhead
                         = (N × 100) - (N × 5)
                         = N × 95

Examples:
- 3 instances → ~285 patients
- 10 instances → ~950 patients
- 20 instances → ~1900 patients
```

---

## 8. Validation Checklist

Before deploying each optimization:

- [ ] Run load test with same patient count
- [ ] Verify throughput improvement ≥ 10%
- [ ] Monitor CPU/memory under new configuration
- [ ] Check for error rate increase
- [ ] Validate message delivery (100% success)
- [ ] Document baseline and new metrics
- [ ] Update monitoring thresholds

---

## Conclusion

By implementing these optimizations progressively, platformeIOT can scale from **50 to 1000+ concurrent patients** while maintaining high reliability and low latency. The phased approach allows for validation and risk mitigation at each stage.

**Key Takeaway:** Horizontal scaling with load balancing is more cost-effective and reliable than trying to optimize single instances beyond their architectural limits.
