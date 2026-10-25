package com.gathin.tvhome;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * news_feed (config.xml, local): headlines from an RSS feed (Google News, India, English by default), in the news section
 * above the dock. MainActivity fetches the feed only while Home is on screen and rotates the headlines.
 */
final class NewsLine {
    static final long REFRESH_MS = 15 * 60_000L, RETRY_MS = 2 * 60_000L;
    private static final int MAX_HEADLINES = 20;

    private NewsLine() {
    }

    /** The feed's item titles, in feed order (top story first). */
    static List<String> headlines(String rss) throws IOException, XmlPullParserException {
        List<String> titles = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(rss));
        boolean inItem = false;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT && titles.size() < MAX_HEADLINES;
             event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) {
                inItem = true;
            } else if (event == XmlPullParser.END_TAG && "item".equals(parser.getName())) {
                inItem = false;
            } else if (inItem && event == XmlPullParser.START_TAG && "title".equals(parser.getName())) {
                String title = parser.nextText().trim();
                if (!title.isEmpty()) titles.add(title);
            }
        }
        return titles;
    }

    /** {headline, outlet}: Google News ends each title with " - Outlet"; the outlet is "" when there is none. */
    static String[] split(String title) {
        int dash = title.lastIndexOf(" - ");
        return dash > 0 ? new String[] {title.substring(0, dash).trim(), title.substring(dash + 3).trim()}
                : new String[] {title, ""};
    }
}
