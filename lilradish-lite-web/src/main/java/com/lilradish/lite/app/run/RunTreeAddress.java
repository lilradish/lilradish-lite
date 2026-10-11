package com.lilradish.lite.app.run;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.run.RunId;

/** The address of a run tree, with no claim that its lock is held. */
record RunTreeAddress(GroupId group, RunId root) {}
