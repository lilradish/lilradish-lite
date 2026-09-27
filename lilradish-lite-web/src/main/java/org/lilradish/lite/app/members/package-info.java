/**
 * One group's members as the store holds them and as a member reads and changes them: who is in it and
 * every role each of them holds there, never how many groups anybody is in or what they hold elsewhere.
 *
 * <p>What is read here is narrowed to the group the gate admitted the caller into and to nothing else,
 * so the permission a handler asks there is the only guard and nothing outside this package reaches it.
 */
@NullMarked
package org.lilradish.lite.app.members;

import org.jspecify.annotations.NullMarked;
