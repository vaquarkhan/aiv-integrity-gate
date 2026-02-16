/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.aiv.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Vaquar Khan
 */
class ChangedFileTest {

    @Test
    void gettersReturnValues() {
        var f = new ChangedFile("p.java", ChangedFile.ChangeType.MODIFIED, "code");
        assertEquals("p.java", f.getPath());
        assertEquals(ChangedFile.ChangeType.MODIFIED, f.getChangeType());
        assertEquals("code", f.getContent());
    }

    @Test
    void nullContentBecomesEmpty() {
        var f = new ChangedFile("x", ChangedFile.ChangeType.ADDED, null);
        assertEquals("", f.getContent());
    }
}
