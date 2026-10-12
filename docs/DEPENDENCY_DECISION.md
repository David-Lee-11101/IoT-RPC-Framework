# Java 8 transport dependency decision

The governed Communication and Logic Engine components use one Java 8 compatible dependency line for their shared implementation families:

- Netty `4.1.67.Final`, already selected by the Logic Engine's Spring Boot 2.5.4 dependency management. The upstream release documents it as a stable 4.1 release: <https://netty.io/news/2021/08/16/4-1-67-Final.html>.
- Fastjson `1.2.72`, already used by the preserved 16-byte transport. Its upstream release explicitly supports older JDKs and therefore Java 8: <https://github.com/alibaba/fastjson/releases/tag/1.2.72>.

The decision removes accidental version divergence without changing the Java baseline or wire protocols. AutoType remains disabled and only explicit DTO classes are parsed. These versions preserve the research implementation; they are not presented as a current production security baseline. A future production hardening pass should migrate the JSON boundary and then-current supported Netty release as a separately reviewed compatibility change.

