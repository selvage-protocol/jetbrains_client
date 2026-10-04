package dev.dontblameme.selvage.intellij.session

import junit.framework.TestCase

/** A host republishes its listing when a file under its folder comes or goes, on Windows too. */
class ListingWatchTest : TestCase() {
    fun testAnEventUnderTheFolderIsSeenWhateverTheSeparator() {
        assertTrue(RoomSession.isUnder("C:/Users/me/proj/src/new.kt", "C:\\Users\\me\\proj"))
        assertTrue(RoomSession.isUnder("C:/Users/me/proj", "C:\\Users\\me\\proj"))
        assertTrue(RoomSession.isUnder("/home/me/proj/src/new.kt", "/home/me/proj"))
        assertTrue(RoomSession.isUnder("/home/me/proj/a", "/home/me/proj/"))
    }

    fun testASiblingThatSharesThePrefixIsNotUnderTheFolder() {
        assertFalse(RoomSession.isUnder("/home/me/proj2/src/new.kt", "/home/me/proj"))
        assertFalse(RoomSession.isUnder("C:/Users/me/proj2/x.kt", "C:\\Users\\me\\proj"))
        assertFalse(RoomSession.isUnder("/home/me", "/home/me/proj"))
    }
}
