package com.bbro.kidtube.core

/**
 * Facebook videos are parents-only for now: while false, the kid feed is built WITHOUT them (they never show, never lock the
 * feed behind a required item, never count toward quota or caps). The Facebook player code stays, switched off. The parent
 * Library and the share sheet still accept Facebook links. `FeedRepository` takes the flag as a constructor argument so tests
 * can flip it; this constant is the production value and the only place to change.
 */
const val KIDS_FACEBOOK_ENABLED = false
