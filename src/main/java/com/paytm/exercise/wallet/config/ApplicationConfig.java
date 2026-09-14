package com.paytm.exercise.wallet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WalletProperties.class)
public class ApplicationConfig { }
