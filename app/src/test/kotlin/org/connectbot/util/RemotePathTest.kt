/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePathTest {
    @Test
    fun extract_AbsoluteAndHomePaths() {
        assertEquals("/home/paul/Projects", RemotePath.extract("/home/paul/Projects"))
        assertEquals("/tmp/app.apk", RemotePath.extract("see /tmp/app.apk)"))
        assertEquals("~/storage/downloads", RemotePath.extract("~/storage/downloads"))
        assertEquals("./dist/app.apk", RemotePath.extract("./dist/app.apk"))
    }

    @Test
    fun extract_SftpAndFileLinks() {
        assertEquals("/home/paul/file", RemotePath.extract("sftp://omarchy-s30/home/paul/file"))
        assertEquals("/home/paul/file", RemotePath.extract("file:///home/paul/file"))
    }

    @Test
    fun extract_IgnoresWebUrls() {
        assertNull(RemotePath.extract("https://example.com/home/paul"))
        assertEquals(listOf("/home/paul"), RemotePath.findAll("https://example.com/x and /home/paul"))
    }

    @Test
    fun extract_StripsPromptSuffix() {
        assertEquals("/home/paul", RemotePath.extract("paul@host:/home/paul$"))
    }
}
