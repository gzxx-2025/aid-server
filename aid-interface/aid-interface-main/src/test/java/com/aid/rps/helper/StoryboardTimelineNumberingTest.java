package com.aid.rps.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aid.aid.domain.AidStoryboard;

class StoryboardTimelineNumberingTest
{
    @Test
    void synchronizesSortOrderDefaultTitleAndShotNumber()
    {
        AidStoryboard storyboard = new AidStoryboard();
        storyboard.setTitle("分镜脚本099");
        storyboard.setScriptParams("{\"镜号\":\"099\",\"剧本内容\":\"甲入场\"}");

        StoryboardTimelineNumbering.synchronize(storyboard, 2L);

        assertEquals(2L, storyboard.getSortOrder());
        assertEquals("分镜脚本002", storyboard.getTitle());
        assertTrue(storyboard.getScriptParams().contains("\"镜号\":\"002\""));
        assertTrue(storyboard.getScriptParams().contains("\"剧本内容\":\"甲入场\""));
    }

    @Test
    void preservesCustomTitle()
    {
        AidStoryboard storyboard = new AidStoryboard();
        storyboard.setTitle("我的转折镜头");
        storyboard.setScriptParams("{}");

        StoryboardTimelineNumbering.synchronize(storyboard, 3L);

        assertEquals("我的转折镜头", storyboard.getTitle());
    }
}
