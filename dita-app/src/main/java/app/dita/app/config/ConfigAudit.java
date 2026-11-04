package app.dita.app.config;

import app.dita.core.config.DeletionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Startup audit (N-26): logs every security-relevant setting that differs from the product-owner default. No secrets. */
public final class ConfigAudit {
    private static final Logger log = LoggerFactory.getLogger(ConfigAudit.class);

    private ConfigAudit() {}

    public static List<String> nonDefaults(DitaSettings s) {
        DeletionSettings d = s.deletion(), def = DeletionSettings.DEFAULTS;
        List<String> out = new ArrayList<>();
        if (!d.earlyWindow().equals(def.earlyWindow())) out.add("dita.deletion.early-window=" + d.earlyWindow());
        if (d.lateKnowledge() != def.lateKnowledge()) out.add("dita.deletion.late-derived-knowledge=" + d.lateKnowledge());
        if (d.repeatedWording() != def.repeatedWording()) out.add("dita.deletion.repeated-wording=" + d.repeatedWording());
        if (!d.groupRemovalRetention().equals(def.groupRemovalRetention())) out.add("dita.deletion.group-removal-retention=" + d.groupRemovalRetention());
        if (d.adminsMayForgetOthers()) out.add("dita.deletion.admins-may-forget-others=true");
        if (d.ditaReplies() != def.ditaReplies()) out.add("dita.deletion.dita-replies=" + d.ditaReplies());
        if (s.raw().memory().historyAccess() != app.dita.core.memory.HistoryAccess.FULL_GROUP_HISTORY)
            out.add("dita.memory.history-access=" + s.raw().memory().historyAccess());
        if (s.raw().telegram().enabled()) out.add("dita.telegram.enabled=true allowed-chat-ids=" + s.raw().telegram().allowedChatIds()
                + " allowed-author-ids=" + s.raw().telegram().allowedAuthorIds().size() + " configured");
        return out;
    }

    static void log(DitaSettings s) {
        List<String> nd = nonDefaults(s);
        if (nd.isEmpty()) log.info("Dita configuration: product-owner defaults for all security-relevant settings");
        else nd.forEach(x -> log.warn("Dita configuration differs from product-owner default: {}", x));
    }
}
