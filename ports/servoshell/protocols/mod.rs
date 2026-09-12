/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! Protocol handlers shared between the desktop shell (`desktop/protocols/`, which has its
//! own additional desktop-only handlers alongside these) and the Android/OpenHarmony EGL
//! shell (`egl/app.rs`) -- unlike `desktop/protocols/`, this module isn't gated to any
//! specific target. Moved out of `desktop/protocols/` (2026-09-12) specifically so
//! `FileProtocolHandler`'s `rebase_to_content_root` fix (root-absolute asset references,
//! the default virtually every bundler emits, resolving against the real OS filesystem root
//! instead of the game's own content root under a bare `file:` URL) could be reused on
//! Android instead of only ever existing for desktop -- see CUSTOMIZATIONS.md's 2026-09-12
//! entry for the real-device bug this was ported to fix.

pub(crate) mod file;
pub(crate) mod packed_content;
