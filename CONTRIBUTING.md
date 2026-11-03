# Contributing

Thank you for wanting to help. **Bug reports, ideas and questions are welcome as GitHub issues.**

## Code contributions are not accepted for now

Pull requests will be closed without being merged. The project has one owner so that its licence stays simple and so
that it can be licensed commercially later without asking anyone else. If you have a fix, describe it in an issue (in
words, or a short explanation of what to change) and the owner will write it.

If this changes, this file will say so first, together with the terms any contribution would be given under.

## If you change your own copy

You are free to change your own copy under the licence (PolyForm Noncommercial 1.0.0). Please keep these true in
anything you share:

- no internet permission or network code, no microphone, no recording;
- no third-party code, fonts, sounds or model weights unless their licence allows it, credited as in
  THIRD-PARTY-NOTICES.md;
- no medical claims, and no diseases or symptoms named in the app's wording.

Tests: `./gradlew :core:test :app:testDebugUnitTest :desktop:test` (the laptop tests that run karaoke, clean a talk
and 8D copy need those tools installed; see README.md).
