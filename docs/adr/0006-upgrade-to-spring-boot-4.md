# 6. Upgrade to Spring Boot 4

Status: accepted

Context: The CI image scan reported critical and high CVEs in spring-webmvc that are fixed only in Spring
Framework 7. Open source support for Spring Boot 3.5 has ended, so no patch for the 6.2 line will come.

Decision: Move to Spring Boot 4.1 (Spring Framework 7, Java 21 stays). Jackson 3 (`tools.jackson`) replaces
Jackson 2 in our own code, springdoc moves to the 3.x line, Flyway comes in through its own starter and the
Testcontainers 2 artifacts are used in tests. Jackson annotations keep their old package. lz4-java and
snappy-java, which kafka-clients pulls in and Boot does not manage, are pinned to fixed versions in
`dependencyManagement`. The runtime image also runs `apt-get upgrade` so OS fixes are applied.

Consequences: Jackson 3 exceptions are unchecked, so the JSON handling in the services got shorter. Dependabot
now groups updates into one weekly pull request per ecosystem and ignores major eclipse-temurin bumps. The
pinned lz4 and snappy versions can be dropped once Boot manages fixed ones.
