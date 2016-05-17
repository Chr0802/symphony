/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com
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
package org.b3log.symphony.event;

import com.scienjus.smartqq.callback.MessageCallback;
import com.scienjus.smartqq.client.SmartQQClient;
import com.scienjus.smartqq.model.DiscussMessage;
import com.scienjus.smartqq.model.Group;
import com.scienjus.smartqq.model.GroupMessage;
import com.scienjus.smartqq.model.Message;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.Latkes;
import org.b3log.latke.event.AbstractEventListener;
import org.b3log.latke.event.Event;
import org.b3log.latke.event.EventException;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.cache.TagCache;
import org.b3log.symphony.model.Article;
import org.b3log.symphony.model.Tag;
import org.b3log.symphony.service.TuringQueryService;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;

/**
 * Sends an article to QQ qun.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.1.0.0, May 17, 2016
 * @since 1.4.0
 */
@Named
@Singleton
public class ArticleQQSender extends AbstractEventListener<JSONObject> {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleQQSender.class.getName());

    /**
     * QQ group names.
     */
    private static final List<String> QQ_GROUP_NAMES = new ArrayList<String>();

    static {
        final String namesStr = Symphonys.get("qq.groupNames");
        if (StringUtils.isNotBlank(namesStr)) {
            final String[] names = namesStr.split(",");
            for (final String name : names) {
                QQ_GROUP_NAMES.add(name);
            }
        }
    }

    /**
     * QQ group ids.
     */
    private static List<Long> QQ_GROUP_IDS = new ArrayList<Long>();

    /**
     * QQ client.
     */
    private SmartQQClient qqClient = null;

    /**
     * Tag cache.
     */
    @Inject
    private TagCache tagCache;

    /**
     * Turing query service.
     */
    @Inject
    private TuringQueryService turingQueryService;

    /**
     * Initializes QQ client.
     */
    public void initQQClient() {
        if (QQ_GROUP_NAMES.isEmpty()) {
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                qqClient = new SmartQQClient(new MessageCallback() {
                    @Override
                    public void onMessage(final Message message) {
                    }

                    @Override
                    public void onGroupMessage(final GroupMessage message) {
                        final long groupId = message.getGroupId();

                        if (QQ_GROUP_IDS.isEmpty() || !QQ_GROUP_IDS.contains(groupId)) {
                            return;
                        }

                        final String content = message.getContent();
                        if (StringUtils.length(content) < 7
                                || (!StringUtils.contains(content, "?") && !StringUtils.contains(content, "？")
                                && !StringUtils.contains(content, "问"))) {
                            return;
                        }

                        String keyword = "";
                        final List<JSONObject> tags = tagCache.getTags();
                        for (final JSONObject tag : tags) {
                            if (tag.optInt(Tag.TAG_REFERENCE_CNT) < 10) {
                                continue;
                            }

                            final String tagTitle = tag.optString(Tag.TAG_TITLE);

                            if (StringUtils.containsIgnoreCase(content, tagTitle)) {
                                keyword = tagTitle;

                                break;
                            }
                        }

                        String msg = "";
                        if (StringUtils.isNotBlank(keyword)) {
                            msg = "这里可能有该问题的答案： "
                                    + Latkes.getServePath() + "/search?key=" + keyword;
                        } else {
                            LOGGER.info(content);

                            if (StringUtils.contains(content, "Bot #1")) {
                                msg = turingQueryService.chat("", content);
                                LOGGER.info(msg);
                            }
                        }

                        if (StringUtils.isNotBlank(msg)) {
                            qqClient.sendMessageToGroup(groupId, msg);
                        }
                    }

                    @Override
                    public void onDiscussMessage(final DiscussMessage message) {
                    }
                });

                // Init group id
                final List<Group> groups = qqClient.getGroupList();
                for (final Group group : groups) {
                    final Long id = group.getId();
                    final String name = group.getName();
                    LOGGER.info(id + ": " + name);

                    if (Strings.containsIgnoreCase(name, QQ_GROUP_NAMES.toArray(new String[0]))) {
                        QQ_GROUP_IDS.add(id);
                    }
                }
            }
        }).start();
    }

    /**
     * Closes QQ client.
     */
    public void closeQQClient() {
        if (null == qqClient) {
            return;
        }

        try {
            qqClient.close();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Closes QQ client failed", e);
        }
    }

    @Override
    public void action(final Event<JSONObject> event) throws EventException {
        final JSONObject data = event.getData();
        LOGGER.log(Level.DEBUG, "Processing an event[type={0}, data={1}] in listener[className={2}]",
                new Object[]{event.getType(), data, ArticleQQSender.class.getName()});

        if (null == qqClient) {
            return;
        }

        try {
            final JSONObject article = data.getJSONObject(Article.ARTICLE);
            final int articleType = article.optInt(Article.ARTICLE_TYPE);
            if (Article.ARTICLE_TYPE_C_DISCUSSION == articleType || Article.ARTICLE_TYPE_C_THOUGHT == articleType) {
                return;
            }

            sendToQQGroup(article);

        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Sends the article to QQ group error", e);
        }
    }

    /**
     * Sends the specified article to QQ group.
     *
     * @param article the specified article
     */
    public void sendToQQGroup(final JSONObject article) {
        final String title = article.optString(Article.ARTICLE_TITLE);
        final String permalink = article.optString(Article.ARTICLE_PERMALINK);

        for (final Long groupId : QQ_GROUP_IDS) {
            qqClient.sendMessageToGroup(groupId, title + " " + Latkes.getServePath() + permalink);
        }
    }

    /**
     * Gets the event type {@linkplain EventTypes#ADD_ARTICLE}.
     *
     * @return event type
     */
    @Override
    public String getEventType() {
        return EventTypes.ADD_ARTICLE;
    }
}
