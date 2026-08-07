package com.aid.common.aid.oss.properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OssPropertiesTest
{
    @Test
    void shouldUseLocalStorageDefaultsAndDeriveOfficialAssetPrefix()
    {
        OssProperties properties = new OssProperties();

        assertThat(properties.getEnabled()).isTrue();
        assertThat(properties.getUploadMode()).isEqualTo("local");
        assertThat(properties.getEffectiveCdnDomain()).isEqualTo("/profile");

        properties.setLocalDomain("https://api.example.com/");
        assertThat(properties.getEffectiveCdnDomain()).isEqualTo("https://api.example.com/profile");
    }

    @Test
    void shouldKeepCloudPublicDomainIndependentFromLocalDomain()
    {
        OssProperties properties = new OssProperties();
        properties.setUploadMode("oss");
        properties.setLocalDomain("https://api.example.com");
        properties.setCdnDomain("https://cdn.example.com");

        assertThat(properties.getEffectiveCdnDomain()).isEqualTo("https://cdn.example.com");
    }
}
