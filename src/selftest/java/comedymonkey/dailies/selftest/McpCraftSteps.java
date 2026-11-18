package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Chapter XII, SRV-10 and SRV-11: a job is posted on McpCraft's job board, claimed and finished while the recording
 * runs, so that the job board moments can be looked for afterwards. McpCraft is reached through its own commands and,
 * to learn the new job's id, by reflection; nothing is compiled against it. Skipped, with a line saying why, when
 * McpCraft is absent, is a version that tells nobody about job board events, or does not do what is asked of it.
 */
final class McpCraftSteps {

    /** The title of the job this step posts, which the moments it causes carry */
    static final String JOB_TITLE = "Dailies self test job";

    /** True once a job has been posted, claimed and finished, so the moments for it are worth looking for */
    static boolean done = false;

    /** The id McpCraft gave the job, for the moments query */
    static String jobId = null;

    private static final String CLIENT_EVENT = "dev.mcpcraft.map.event.JobBoardClientEvent";
    private static final String NOT_TRIED = ": job board moments were not tried (SRV-10, SRV-11, SRV-13)";

    private McpCraftSteps() {
    }

    static void run(SelfTest test) {
        if (!Host.get().isModLoaded("mcpcraft")) {
            test.skip("McpCraft is not installed" + NOT_TRIED);
            return;
        }
        try {
            Class.forName(CLIENT_EVENT);
        } catch (Throwable t) {
            test.skip("this version of McpCraft does not post " + CLIENT_EVENT + ", so its job board says nothing to other mods" + NOT_TRIED);
            return;
        }
        // From here McpCraft is installed and new enough, so a job board that does not work is a fault, not a skip (TEST-1)
        try {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                test.fault("there is no built-in server to post a job on" + NOT_TRIED);
                return;
            }
            Board board = new Board();
            // Jobs belong to a map network, and a new world has none until one is made
            String problem = server.submit(board::open).get(30, TimeUnit.SECONDS);
            if (problem != null) {
                test.fault("McpCraft's job board could not be opened (" + problem + ")" + NOT_TRIED);
                return;
            }
            List<String> before = server.submit(board::jobs).get(30, TimeUnit.SECONDS);
            test.command("map job create custom 8 " + JOB_TITLE);
            test.waitTicks(10);
            List<String> after = new ArrayList<>(server.submit(board::jobs).get(30, TimeUnit.SECONDS));
            after.removeAll(before);
            if (after.isEmpty()) {
                test.fault("McpCraft posted no job for '/map job create custom'" + NOT_TRIED);
                return;
            }
            String id = after.get(after.size() - 1);
            test.command("map job claim " + id);
            test.waitTicks(10);
            String claimed = server.submit(() -> board.status(id)).get(30, TimeUnit.SECONDS);
            test.command("map job done " + id);
            test.waitTicks(20);
            String finished = server.submit(() -> board.status(id)).get(30, TimeUnit.SECONDS);
            if (!"claimed".equals(claimed) || !"done".equals(finished)) {
                test.fault("McpCraft's job " + id + " was '" + claimed + "' after claiming and '" + finished + "' after finishing, not 'claimed' and 'done'" + NOT_TRIED);
                return;
            }
            jobId = id;
            done = true;
            test.log("McpCraft job " + id + " posted, claimed and finished");
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies self test: McpCraft's job board could not be driven", t);
            test.fault("McpCraft's job board could not be driven (" + t + ")" + NOT_TRIED);
        }
    }

    /** McpCraft's store of jobs, found by name once. Everything here runs on the server thread */
    private static final class Board {

        private Object store;
        private Method ids, get;

        /** Finds the default map network, making it when the world has none. Returns what went wrong, or null */
        String open() {
            try {
                Class<?> networks = Class.forName("dev.mcpcraft.map.Networks");
                String slug = (String) Class.forName("dev.mcpcraft.Config").getField("mapDefaultSlug").get(null);
                this.store = networks.getMethod("get", String.class).invoke(null, slug);
                if (this.store == null) {
                    this.store = networks.getMethod("create", String.class).invoke(null, slug);
                }
                if (this.store == null) {
                    return "no map network '" + slug + "' could be made";
                }
                this.ids = this.store.getClass().getMethod("ids", String.class);
                this.get = this.store.getClass().getMethod("get", String.class, String.class);
                return null;
            } catch (Throwable t) {
                return String.valueOf(t.getCause() != null ? t.getCause() : t);
            }
        }

        /** The ids of every job on the board */
        List<String> jobs() {
            try {
                List<String> found = new ArrayList<>();
                for (Object id : (List<?>) this.ids.invoke(this.store, "jobs")) {
                    found.add(String.valueOf(id));
                }
                return found;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("McpCraft's jobs could not be listed", e);
            }
        }

        /** A job's status as McpCraft keeps it: open, claimed or done. "gone" when there is no such job */
        String status(String id) {
            try {
                Object job = this.get.invoke(this.store, "jobs", id);
                if (!(job instanceof com.google.gson.JsonObject json) || !json.has("status")) {
                    return "gone";
                }
                return json.get("status").getAsString();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("McpCraft's job " + id + " could not be read", e);
            }
        }
    }

}
