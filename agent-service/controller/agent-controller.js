import { query } from '../db.js';
import { OpenAI } from 'openai';
import { MemoryClient } from 'mem0ai';
import { exploreCodebase } from '../tools.js';

const openai = new OpenAI({
    baseURL: 'https://integrate.api.nvidia.com/v1',
    apiKey: process.env.NVIDIA_API_KEY
});

const mem0 = new MemoryClient({
    apiKey: process.env.MEM0_API_KEY || 'dummy_key_if_none_provided'
});

const SYSTEM_PROMPT = `You are the Nexus Code Expert, an elite senior developer. You have access to the full, unfiltered codebase via the exploreCodebase tool.

RULES:
1. The current Project ID will be provided at the start of every user message: [PROJECT_ID: <uuid>].
2. NEVER hallucinate code. Answer purely based on the tool's output. You now receive the ENTIRE raw code for files, so provide deep, highly accurate technical analysis.
3. DECISION TREE FOR TOOL USAGE:
   - For a structural overview of EVERY file -> Use 'PROJECT_OVERVIEW'.
   - If asked about a SPECIFIC file (e.g., 'prescription service') -> Use 'SEARCH_NODE_BY_NAME' with the name. Then use 'NODE_DETAIL' with the returned ID to get the full raw code.
   - If asked about a CATEGORY (e.g., 'Explain the services', 'What controllers are there?') -> Use 'GET_NODES_BY_TYPE' and pass the category. You will receive the FULL raw code for every matching file.
4. If a tool returns an error or no data, state it clearly. Do not loop.
5. Provide your final technical explanation immediately after the tool returns the code.`;

export const getHistory = async (req, res) => {
    try {
        const { sessionId } = req.params;
        const result = await query('SELECT id, content, role FROM chat_message WHERE session_id = $1 ORDER BY created_at ASC', [sessionId]);
        const history = result.rows.map(row => ({
            id: row.id,
            content: row.content,
            role: row.role
        }));
        res.json(history);
    } catch (error) {
        console.error("Error fetching history:", error);
        res.status(500).json({ error: "Failed to fetch history" });
    }
};

export const closeSession = async (req, res) => {
    try {
        const { sessionId } = req.params;
        const result = await query('SELECT status FROM chat_session WHERE session_id = $1', [sessionId]);
        if (result.rows.length === 0 || result.rows[0].status === 'CLOSED') {
            return res.json({ message: "Session already closed or not found" });
        }
        
        const historyRes = await query('SELECT role, content FROM chat_message WHERE session_id = $1 ORDER BY created_at ASC', [sessionId]);
        const fullChat = historyRes.rows.map(msg => `${msg.role}: ${msg.content}`).join('\n');
        
        const summaryCompletion = await openai.chat.completions.create({
            model: "meta/llama-3.1-70b-instruct",
            messages: [
                { role: "system", content: "You are a helpful summarizer. Summarize the following technical code conversation." },
                { role: "user", content: fullChat }
            ],
            temperature: 0.2
        });
        const summary = summaryCompletion.choices[0].message.content;
        
        await query('UPDATE chat_session SET status = $1, summary = $2, updated_at = NOW() WHERE session_id = $3', ['CLOSED', summary, sessionId]);
        
        res.json({ message: "Session closed and summarized successfully", summary });
    } catch (error) {
        console.error("Error closing session:", error);
        res.status(500).json({ error: "Failed to close session" });
    }
};

export const sendMessage = async (req, res) => {
    const { sessionId, projectId, message } = req.body;
    
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');

    try {
        const sessionRes = await query('SELECT project_id, status FROM chat_session WHERE session_id = $1', [sessionId]);
        let effectiveProjectId = projectId;
        if (sessionRes.rows.length === 0) {
            await query('INSERT INTO chat_session (session_id, project_id, status, updated_at) VALUES ($1, $2, $3, NOW())', [sessionId, projectId, 'ACTIVE']);
        } else {
            const dbProjectId = sessionRes.rows[0].project_id;
            if ((!dbProjectId || dbProjectId.trim() === '') && projectId) {
                await query('UPDATE chat_session SET project_id = $1 WHERE session_id = $2', [projectId, sessionId]);
            } else if (!projectId && dbProjectId) {
                effectiveProjectId = dbProjectId;
            }
        }

        await query('INSERT INTO chat_message (session_id, role, content, created_at) VALUES ($1, $2, $3, NOW())', [sessionId, 'USER', message]);

        const historyRes = await query('SELECT role, content FROM chat_message WHERE session_id = $1 ORDER BY created_at ASC', [sessionId]);
        const messages = historyRes.rows.map(row => ({
            role: row.role === 'USER' ? 'user' : 'assistant',
            content: row.content
        }));

        let memoryContext = "";
        try {
            const memories = await mem0.search(message, { filters: { user_id: sessionId } });
            if (memories && memories.results && memories.results.length > 0) {
                memoryContext = "\n\nRelevant past knowledge:\n" + memories.results.map(m => m.memory).join('\n');
            }
        } catch (e) {
            console.error("Mem0 search error:", e.message);
        }

        const enrichedPrompt = `${effectiveProjectId ? `[PROJECT_ID: ${effectiveProjectId}]\n` : ""}${message}${memoryContext}`;
        messages[messages.length - 1].content = enrichedPrompt;

        const tools = [{
            type: "function",
            function: {
                name: "exploreCodebase",
                description: "Deep dive into the codebase. Extract complete architecture or exact raw code for deep analysis.",
                parameters: {
                    type: "object",
                    properties: {
                        requestType: { type: "string", description: "Must be 'PROJECT_OVERVIEW', 'NODE_DETAIL', 'SEARCH_NODE_BY_NAME', or 'GET_NODES_BY_TYPE'" },
                        targetId: { type: "string", description: "Pass Project ID UUID or Node ID UUID." },
                        searchQuery: { type: "string", description: "Pass exact file name OR category (like 'SERVICE', 'CONTROLLER')." }
                    },
                    required: ["requestType", "targetId"]
                }
            }
        }];

        let responseStream = await openai.chat.completions.create({
            model: "meta/llama-3.1-70b-instruct",
            messages: [
                { role: "system", content: SYSTEM_PROMPT },
                ...messages
            ],
            tools: tools,
            tool_choice: "auto",
            temperature: 0.1, // Lowered temperature for maximum technical accuracy
            stream: true
        });

        let fullAgentResponse = "";
        let toolCallDetected = false;
        let toolCallName = "";
        let toolCallArgs = "";

        for await (const chunk of responseStream) {
            const delta = chunk.choices[0]?.delta;
            if (delta?.tool_calls) {
                toolCallDetected = true;
                if (delta.tool_calls[0].function?.name) toolCallName += delta.tool_calls[0].function.name;
                if (delta.tool_calls[0].function?.arguments) toolCallArgs += delta.tool_calls[0].function.arguments;
            } else if (delta?.content) {
                fullAgentResponse += delta.content;
                res.write(`data: ${JSON.stringify({ content: delta.content })}\n\n`);
            }
        }

        if (toolCallDetected && toolCallName === "exploreCodebase") {
            res.write(`data: ${JSON.stringify({ content: "\n*Executing deep codebase scan...*\n" })}\n\n`);
            
            let parsedArgs = {};
            try { parsedArgs = JSON.parse(toolCallArgs); } catch (e) {}

            const toolResult = await exploreCodebase(parsedArgs.requestType, parsedArgs.targetId, parsedArgs.searchQuery);
            
            messages.push({
                role: "assistant",
                content: null,
                tool_calls: [{
                    id: "call_deep_1",
                    type: "function",
                    function: { name: toolCallName, arguments: toolCallArgs }
                }]
            });
            messages.push({
                role: "tool",
                tool_call_id: "call_deep_1",
                name: toolCallName,
                content: toolResult
            });

            const finalStream = await openai.chat.completions.create({
                model: "meta/llama-3.1-70b-instruct",
                messages: [
                    { role: "system", content: SYSTEM_PROMPT },
                    ...messages
                ],
                temperature: 0.1,
                stream: true
            });

            for await (const chunk of finalStream) {
                const delta = chunk.choices[0]?.delta;
                if (delta?.content) {
                    fullAgentResponse += delta.content;
                    res.write(`data: ${JSON.stringify({ content: delta.content })}\n\n`);
                }
            }
        }

        if (!fullAgentResponse) {
            fullAgentResponse = "I'm sorry, I couldn't generate a deep analysis. Please try again.";
            res.write(`data: ${JSON.stringify({ content: fullAgentResponse })}\n\n`);
        }

        await query('INSERT INTO chat_message (session_id, role, content, created_at) VALUES ($1, $2, $3, NOW())', [sessionId, 'ASSISTANT', fullAgentResponse]);
        await query('UPDATE chat_session SET updated_at = NOW() WHERE session_id = $1', [sessionId]);

        try { mem0.add([{ role: "user", content: message }, { role: "assistant", content: fullAgentResponse }], { filters: { user_id: sessionId } }).catch(e => console.error(e)); } catch(e) {}

        res.write('data: [DONE]\n\n');
        res.end();
    } catch (error) {
        console.error("Deep Agent error:", error);
        res.write(`data: ${JSON.stringify({ error: error.message || 'Fatal error during deep analysis.' })}\n\n`);
        res.end();
    }
};