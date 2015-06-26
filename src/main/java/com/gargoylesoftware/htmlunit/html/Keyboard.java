/*
 * Copyright (c) 2002-2015 Gargoyle Software Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gargoylesoftware.htmlunit.html;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of the typed keys.
 *
 * @version $Revision$
 * @author Ahmed Ashour
 */
public class Keyboard {

    private List<Object[]> keys_ = new ArrayList<>();

    /**
     * Types the specified character.
     * @param ch the character
     */
    public void type(final char ch) {
        keys_.add(new Object[] {ch});
    }

    /**
     * Press the specified key code (without releasing it).
     *
     * An example of predefined values is
     * {@link com.gargoylesoftware.htmlunit.javascript.host.event.KeyboardEvent#DOM_VK_PAGE_DOWN}.
     *
     * @param keyCode the key code
     */
    public void press(final int keyCode) {
        keys_.add(new Object[] {keyCode, true});
    }

    /**
     * Releases the specified key code.
     *
     * An example of predefined values is
     * {@link com.gargoylesoftware.htmlunit.javascript.host.event.KeyboardEvent#DOM_VK_PAGE_DOWN}.
     *
     * @param keyCode the key code.
     */
    public void release(final int keyCode) {
        keys_.add(new Object[] {keyCode, false});
    }

    /**
     * Clears all keys.
     */
    public void clear() {
        keys_.clear();
    }

    /**
     * Returns the keys.
     *
     * If the length of the item is 1, then it is a character.
     * If the length of the item is 2, the first is the key code, the second is boolean whether pressing or not
     *
     * @return the keys
     */
    List<Object[]> getKeys() {
        return keys_;
    }
}
