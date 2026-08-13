# Support multiple device sessions for one profile

An Engine will continue to open only one Profile at a time, but that Profile may authorize multiple concurrent Device Sessions. Each device can be listed and revoked independently; opening a different Profile suspends the previous Profile's online access without revoking its Device Sessions, as specified by ADR-0045. Turning the Engine into a general multi-user service remains outside scope.
