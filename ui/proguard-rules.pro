# Trigger and action implementations are resolved by their `type` string, not by
# class reference, so R8 cannot see the link from a stored rule to its class.
# The factory lists in :triggers and :actions do reference them directly, which
# keeps them reachable — if that ever changes to reflection, add keep rules here.
