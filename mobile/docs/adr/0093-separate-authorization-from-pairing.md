---
status: accepted
---

# Separate authorization from Pairing

Pairing owns the disposable onboarding flow that grants a Client installation its durable Device
Session, while a separate Authorization feature owns challenge acquisition, Device Proof, and
short-lived Access Session behavior. This keeps the recurring connection and renewal lifecycle out
of the one-time Pairing boundary, at the cost of an explicit contract between the two features;
`:shared` composes both behind the stable native facade without making either feature depend on the
other's implementation.
