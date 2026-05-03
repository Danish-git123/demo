import express from "express";
import cors from "cors";
import dotenv from "dotenv";
import { getHistory, closeSession, sendMessage } from "./controller/agent-controller.js";

dotenv.config();

const app = express();

app.use(cors());
app.use(express.json());

app.get("/api/chat/:sessionId/history", getHistory);
app.post("/api/chat/:sessionId/close", closeSession);
app.post("/api/chat/message", sendMessage);

app.listen(5000, () => {
    console.log("AgentServer started on port 5000");
});