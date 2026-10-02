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
                // the consent surface (C06) is a domain API like the others.
                "privacy"
		}
)
package com.aifashionvault.platform;
