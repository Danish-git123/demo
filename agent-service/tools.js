import { query } from './db.js';

export const exploreCodebase = async (requestType, targetId, searchQuery = "") => {
    
    // 1. FULL PROJECT OVERVIEW - No limits, returns every single node
    if (requestType === 'PROJECT_OVERVIEW') {
        try {
            const result = await query(
                `SELECT id, label, node_type, file_path 
                 FROM ast_nodes 
                 WHERE project_id = $1 
                 ORDER BY file_path ASC, label ASC`, 
                [targetId]
            );

            if (result.rows.length === 0) return "Project is empty or not found in the database.";

            // Grouping by folder path for a clean, deep structural view
            const groupedNodes = {};
            result.rows.forEach(row => {
                // Extract folder path or use 'Root'
                let folder = 'Root';
                if (row.file_path) {
                    const pathParts = row.file_path.split('/');
                    if (pathParts.length > 1) {
                        pathParts.pop(); // Remove file name
                        folder = pathParts.join('/');
                    }
                }
                
                if (!groupedNodes[folder]) groupedNodes[folder] = [];
                groupedNodes[folder].push(`- [${row.node_type}] ${row.label} (ID: ${row.id})`);
            });

            let overviewText = "Here is the COMPLETE project structure containing EVERY node:\n\n";
            for (const [folder, files] of Object.entries(groupedNodes)) {
                overviewText += `📁 ${folder}\n${files.join('\n')}\n\n`;
            }

            return overviewText;
        } catch (error) {
            console.error("Error generating deep overview:", error);
            return "Failed to generate complete project overview.";
        }
    } 
    
    // 2. SEARCH EXACT NODE BY NAME - Clean search
    else if (requestType === 'SEARCH_NODE_BY_NAME') {
        try {
            const cleanSearch = searchQuery.replace(/\s+/g, '');
            const searchPattern = `%${cleanSearch}%`;
            
            const result = await query(
                `SELECT id, label, node_type, file_path 
                 FROM ast_nodes 
                 WHERE project_id = $1 AND REPLACE(label, ' ', '') ILIKE $2`, 
                [targetId, searchPattern]
            );

            if (result.rows.length === 0) return `No nodes found matching exact name "${searchQuery}".`;

            let response = `Found matching nodes for "${searchQuery}". Use the correct ID for NODE_DETAIL:\n`;
            result.rows.forEach(row => {
                response += `- ${row.label} | Type: ${row.node_type} | Path: ${row.file_path} | ID: ${row.id}\n`;
            });
            return response;

        } catch (error) {
            console.error("Error searching exact node:", error);
            return `Database error while searching for specific node.`;
        }
    }
    
    // 3. GET EXACT RAW CODE - NO TRUNCATION
    else if (requestType === 'NODE_DETAIL') {
        try {
            const result = await query(
                `SELECT label, file_path, raw_code, ai_explanation, dependencies 
                 FROM ast_nodes WHERE id = $1`, 
                [targetId]
            );
            
            if (result.rows.length === 0) return "Node ID not found in database.";
            
            const node = result.rows[0];
            let response = `--- NODE DETAIL: ${node.label} ---\n`;
            response += `File Path: ${node.file_path}\n`;
            response += `Dependencies: ${node.dependencies || 'None'}\n\n`;
            
            if (node.ai_explanation) {
                response += `Cached AI Explanation:\n${node.ai_explanation}\n\n`;
            }
            
            // SENDING THE ENTIRE RAW CODE. NO LIMITS.
            response += `FULL RAW CODE:\n\`\`\`\n${node.raw_code || 'No raw code available.'}\n\`\`\``;
            
            return response;
        } catch (error) {
            console.error("Error fetching node detail:", error);
            return "Error fetching raw code for the node.";
        }
    }

    // 4. GET ALL NODES IN A CATEGORY - FULL CONTEXT
    else if (requestType === 'GET_NODES_BY_TYPE') {
        try {
            let cleanType = searchQuery.toUpperCase().replace(/S$/, ''); 
            let searchPattern = `%${cleanType}%`;
            
            const result = await query(
                `SELECT label, file_path, node_type, raw_code, ai_explanation 
                 FROM ast_nodes 
                 WHERE project_id = $1 AND (
                     node_type ILIKE $2 OR 
                     label ILIKE $2 OR 
                     file_path ILIKE $2
                 )`, 
                [targetId, searchPattern]
            );

            if (result.rows.length === 0) return `No nodes found matching category "${searchQuery}".`;

            let response = `Found ${result.rows.length} complete components matching "${searchQuery}":\n\n`;
            
            result.rows.forEach(row => {
                // SENDING THE FULL RAW CODE FOR DEEP ANALYSIS
                const codeContent = row.raw_code ? row.raw_code : 'No raw code available in DB.';
                const aiExplanation = row.ai_explanation ? `\nAI Summary: ${row.ai_explanation}` : '';
                
                response += `=========================================\n`;
                response += `COMPONENT: ${row.label} [Type: ${row.node_type}]\n`;
                response += `PATH: ${row.file_path}${aiExplanation}\n`;
                response += `FULL CODE:\n\`\`\`\n${codeContent}\n\`\`\`\n`;
                response += `=========================================\n\n`;
            });
            
            return response;

        } catch (error) {
            console.error("Error fetching category nodes:", error);
            return `Database error while fetching category nodes.`;
        }
    }
    
    return "Invalid requestType";
};