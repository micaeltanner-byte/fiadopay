#!/usr/bin/env powershell
Write-Output "Building project (skip tests)..."
./mvnw.cmd -DskipTests=true package
Write-Output "Starting Spring Boot application..."
./mvnw.cmd spring-boot:run
