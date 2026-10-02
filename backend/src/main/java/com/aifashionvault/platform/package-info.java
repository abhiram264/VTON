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
                // The generation composition layer emits C22 lifecycle events
                // through the analytics module's ProductMetrics seam.
                "analytics"
		}
)
package com.aifashionvault.platform;
