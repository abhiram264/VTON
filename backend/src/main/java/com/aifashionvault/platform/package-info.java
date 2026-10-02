@org.springframework.modulith.ApplicationModule(
		allowedDependencies = {
                "generation :: vton",
                "generation :: benchmark",
                "shared",
                "avatar",
                "user",
                "generation",
                "admission",
                "integrity",
                "look",
                "notification",
                "importer",
                "retention",
                "collection",
                "vault",
                "normalization",
                // The composition layer mounts every domain's HTTP surface, and
                // the consent surface (C06) and studio surface (C10) are domain
                // APIs like the others.
                "privacy",
                "studio",
                "studio :: agent",
                // The composition layer emits C22 lifecycle events through the
                // analytics module's ProductMetrics seam and consults the capability
                // flags at the HTTP boundary (C22), so both are composition-layer
                // dependencies like the surfaces above.
                "analytics",
                "featureflag"
		}
)
package com.aifashionvault.platform;
