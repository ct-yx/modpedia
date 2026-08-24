package io.ctyx.modpedia.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 1.12.2 会话中的一条消息。 */
public final class LegacyChatMessage {
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    private final Role role;
    private final String markdown;
    private final List<LegacySourceReference> sources;
    private final List<String> followUpQuestions;

    public LegacyChatMessage(Role role, String markdown) {
        this(role, markdown, Collections.<LegacySourceReference>emptyList(),
                Collections.<String>emptyList());
    }

    public LegacyChatMessage(Role role, String markdown,
            List<LegacySourceReference> sources, List<String> followUpQuestions) {
        this.role = role == null ? Role.SYSTEM : role;
        this.markdown = markdown == null ? "" : markdown;
        this.sources = immutableSources(sources);
        this.followUpQuestions = immutableStrings(followUpQuestions);
    }

    public Role getRole() {
        return role;
    }

    public String getMarkdown() {
        return markdown;
    }

    public List<LegacySourceReference> getSources() {
        return sources;
    }

    public List<String> getFollowUpQuestions() {
        return followUpQuestions;
    }

    public LegacyChatMessage withMarkdown(String value) {
        return new LegacyChatMessage(role, value, sources, followUpQuestions);
    }

    public LegacyChatMessage withDetails(List<LegacySourceReference> value,
            List<String> followUps) {
        return new LegacyChatMessage(role, markdown, value, followUps);
    }

    private static List<LegacySourceReference> immutableSources(
            List<LegacySourceReference> value) {
        List<LegacySourceReference> result = new ArrayList<LegacySourceReference>();
        if (value != null) {
            for (LegacySourceReference source : value) {
                if (source != null) {
                    result.add(source);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> immutableStrings(List<String> value) {
        List<String> result = new ArrayList<String>();
        if (value != null) {
            for (String item : value) {
                if (item != null && !item.trim().isEmpty()) {
                    result.add(item.trim());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
}
