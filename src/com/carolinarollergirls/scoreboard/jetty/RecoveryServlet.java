package com.carolinarollergirls.scoreboard.jetty;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.jr.ob.JSON;

import com.carolinarollergirls.scoreboard.core.interfaces.ScoreBoard;
import com.carolinarollergirls.scoreboard.json.Recovery;
import com.carolinarollergirls.scoreboard.utils.Logger;

/**
 * The Recovery screen's server side.
 * <ul>
 * <li>{@code GET /Recovery/list}: games and their restore points, as JSON.</li>
 * <li>{@code POST /Recovery/restore?path=…&game=…}: restore a game to a restore point.</li>
 * </ul>
 * Only the computer CRG runs on may use it: the request must come from a loopback address and not through a proxy
 * (which says so in {@code X-Forwarded-For} or {@code X-CRG-Forwarded-For}).
 */
public class RecoveryServlet extends HttpServlet {
    public RecoveryServlet(ScoreBoard sb) { scoreBoard = sb; }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-cache");
        if (!"/list".equals(request.getPathInfo())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("local", isLocal(request));
        out.put("games", Recovery.list(scoreBoard));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(JSON.std.asString(out));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-cache");
        if (!"/restore".equals(request.getPathInfo())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!isLocal(request)) {
            text(response, HttpServletResponse.SC_FORBIDDEN,
                 "Restoring is only possible on the computer the scoreboard runs on.");
            return;
        }
        String path = request.getParameter("path");
        String game = request.getParameter("game");
        if (path == null || game == null) {
            text(response, HttpServletResponse.SC_BAD_REQUEST, "path and game are required");
            return;
        }
        try {
            String newId = Recovery.restore(scoreBoard, path, game);
            text(response, HttpServletResponse.SC_OK, newId);
        } catch (IOException e) {
            text(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            Logger.printStackTrace("restoring " + path, e);
            text(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Restore failed: " + e);
        }
    }

    static boolean isLocal(HttpServletRequest request) {
        if (request.getHeader("X-Forwarded-For") != null || request.getHeader("X-CRG-Forwarded-For") != null) {
            return false;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (IOException e) { return false; }
    }

    private static void text(HttpServletResponse response, int code, String text) throws IOException {
        response.setStatus(code);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(text);
    }

    private final ScoreBoard scoreBoard;
}
