package com.aifashionvault.analytics;

public interface ProductMetrics {
    void record(AnalyticsEventName eventName, ProductMetricContext context);
}
