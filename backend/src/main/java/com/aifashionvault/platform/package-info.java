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
                "normalization"
		}
)
package com.aifashionvault.platform;
