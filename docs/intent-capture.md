# Learn an intent by catching one

The `fire_intent` action can send an intent. Nothing on the phone tells a person
which intent to send. This document holds the plan that closes that gap. The
plan is written down and it is not built.

## The problem this exists for

An intent has an action string and a set of values. Trigly can send it. But
Android keeps no catalogue of the intents an app accepts, and it offers no way
to ask for one. There is no index and no query.

So a person must already know the intent before Trigly can send it. Almost
nobody knows it. The action is built, and without this it stays unused by
everybody except the small group who read manifests for fun.

This is the same class of problem as `open_app` and package visibility, and it
has the same shape: the platform holds the answer and gives the app no way to
read it.

## The plan

Do not read the intent. Catch one.

Trigly makes itself a target in the Android share menu. A person shares
something to Trigly, in the same way that they share a photo to a chat app.
Trigly does not act on the item. Trigly shows the intent that arrived, in plain
words:

- the action string,
- the MIME type,
- each extra, with its key.

Trigly then offers two buttons. **Copy** puts the intent on the clipboard.
**Make a rule** opens the rule editor with a `fire_intent` action already
filled in. The person adds a trigger, and the rule is done.

The person never types an action string. They never read a manual. They do the
thing once by hand, and Trigly keeps the shape of it.

The other app writes the intent. Trigly only reads it back.

## What the plan does not do

- It does not keep the item. The text, the photo or the link is shown and then
  dropped. Nothing about the content goes into the rule database.
- It does not send anything. Trigly is the receiver here, not the sender.
- It does not list every intent an app accepts. It shows the one that arrived.
- It does not work for an app with no share function. That limit is real, and
  the plan does not hide it.

## Why this route and not another

Three other routes exist. Each one fails for a clear reason, and the reasons
are worth keeping so that nobody opens them again.

**Read the other app's manifest.** Trigly could list the intent filters another
app declares. A declared filter is not a working intent. The list would be long,
and most of it would fail when a rule tried to use it. A rule that fails quietly
is the one thing this project is built to avoid.

**Hold a list of standard intents and test each one.** This answers the wrong
question. It says which apps take a given intent. It does not say which intents
a given app takes.

**Ask the person to type it.** This works for the people who already know the
answer. Those people do not need the feature.

Capture is the only route where the answer comes from the app itself. It is also
the only route where the answer is known to work, because it worked once
already.

## Build order

1. **Catch and show.** Trigly appears in the share menu and shows what arrived.
   This alone is useful, because a person can copy the result by hand.
2. **Make a rule.** The button that fills the editor.

Step 1 is small and it answers the question on its own. Build it, share three
items from three apps, and read what really comes back. Decide step 2 with that
in hand, and not before.

The **Test** button is the third part of the same idea, and it is built. It
reports whether a configured intent would land, and it never sends one. It turns
a silent night time failure into a message the person sees while they write the
rule. See `docs/actions.md`.

## Decide first

Three things need an answer before any of this is code.

**Which MIME types Trigly claims in the share menu.** Text alone is the narrow
claim. Adding a link makes Trigly a candidate in some browser share menus, and a
person who wanted their browser gets an automation app in the list. That is a
real cost to everybody who never uses this feature, paid so that a few people
can capture a link intent. Claiming `*/*` is worse again for the same reason.

**Whether a caught intent is kept or dropped.** Dropped is simpler and safer,
because nothing shared to Trigly is then stored anywhere. Kept is friendlier,
because a person can share three things and then choose. If it is kept, it needs
a lifetime, a place to live, and an answer for what happens when the process
dies with a capture in it.

**Whether the share target is worth the entry in every share menu on the phone.**
This is the cost the feature cannot avoid. Trigly appears in a system menu that
a person opens many times a day for reasons that have nothing to do with
automation. A setting that turns the share target off is the obvious answer, and
a setting that defaults to off makes the feature invisible to the people it was
built for. That tension is the decision.

## Done when

Either step 1 is built and three real captures are written down here with what
came back, or this document gains a section saying which of the three decisions
killed it and why.
