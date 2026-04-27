import React, { useState, useRef, useEffect } from 'react';
import { MessageSquare, X, Send, Bot, User, Loader2 } from 'lucide-react';
import { useAuth } from '../hooks/useAuth.js';

export default function AgentChatWindow({ projectId, activeNodeId, detectedFramework }) {
  const [isOpen, setIsOpen] = useState(() => {
    const saved = sessionStorage.getItem('nexus_chat_isOpen');
    sessionStorage.removeItem('nexus_chat_isOpen');
    return saved === 'true';
  });
  const [messages, setMessages] = useState(() => {
    const saved = sessionStorage.getItem('nexus_chat_messages');
    sessionStorage.removeItem('nexus_chat_messages');
    if (saved) {
      try { return JSON.parse(saved); } catch(e) {}
    }
    return [
      { id: 1, text: "Hi! I'm the Nexus Agent. Ask me anything about the repository architecture or specific components.", sender: 'agent' }
    ];
  });
  const [input, setInput] = useState('');
  const [isTyping, setIsTyping] = useState(false);
  const messagesEndRef = useRef(null);
  const { getToken } = useAuth();
  const [conversationId] = useState(() => {
    const saved = sessionStorage.getItem('nexus_chat_conversationId');
    sessionStorage.removeItem('nexus_chat_conversationId');
    if (saved) return saved;
    return crypto.randomUUID();
  });

  useEffect(() => {
    const handleBeforeUnload = () => {
      sessionStorage.setItem('nexus_chat_isOpen', isOpen);
      sessionStorage.setItem('nexus_chat_messages', JSON.stringify(messages));
      sessionStorage.setItem('nexus_chat_conversationId', conversationId);
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isOpen, messages, conversationId]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    if (isOpen) {
      scrollToBottom();
    }
  }, [messages, isOpen]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!input.trim() || !projectId) return;

    const userMessage = input.trim();
    setInput('');
    const newMessageId = Date.now();
    
    setMessages(prev => [...prev, { id: newMessageId, text: userMessage, sender: 'user' }]);
    setIsTyping(true);

    const agentMessageId = newMessageId + 1;
    setMessages(prev => [...prev, { id: agentMessageId, text: '', sender: 'agent' }]);

    try {
      const token = await getToken();
      
      const payload = {
        projectId: projectId,
        activeNodeId: activeNodeId || null,
        userPrompt: userMessage,
        detectedFramwork: detectedFramework || "Unknown",
        conversationId: conversationId
      };

      const response = await fetch('/api/agent/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify(payload)
      });

      if (!response.ok) {
        throw new Error('Failed to connect to agent');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let done = false;
      let agentReply = '';

      while (!done) {
        const { value, done: readerDone } = await reader.read();
        done = readerDone;
        if (value) {
          const chunk = decoder.decode(value, { stream: true });
          
          // SSE format: "data: chunk content\n\n"
          const lines = chunk.split('\n');
          for (let line of lines) {
            if (line.startsWith('data:')) {
              const dataContent = line.replace('data:', '').trim();
              if (dataContent === '[DONE]') {
                done = true;
                break;
              }
              // Replace literal \n with actual newlines if backend sends escaped newlines
              const parsedContent = dataContent.replace(/\\n/g, '\n');
              agentReply += parsedContent;
              
              setMessages(prev => 
                prev.map(msg => 
                  msg.id === agentMessageId ? { ...msg, text: agentReply } : msg
                )
              );
            }
          }
        }
      }
    } catch (error) {
      console.error("Agent error:", error);
      setMessages(prev => 
        prev.map(msg => 
          msg.id === agentMessageId ? { ...msg, text: 'Sorry, I encountered an error. Please try again.' } : msg
        )
      );
    } finally {
      setIsTyping(false);
    }
  };

  if (!projectId) return null; // Don't render chat if no project is loaded

  return (
    <>
      {/* Floating Toggle Button */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className={`fixed bottom-6 right-6 p-4 rounded-full shadow-2xl transition-all duration-300 z-50 ${
          isOpen ? 'bg-dark-800 border border-gray-700 hover:bg-dark-700 text-gray-300' : 'bg-primary-600 hover:bg-primary-500 text-white hover:scale-110'
        }`}
        title="Chat with Agent"
      >
        {isOpen ? <X className="w-6 h-6" /> : <MessageSquare className="w-6 h-6" />}
      </button>

      {/* Chat Window */}
      <div
        className={`fixed bottom-24 right-6 w-[380px] max-w-[calc(100vw-3rem)] h-[550px] max-h-[calc(100vh-8rem)] bg-dark-800/95 backdrop-blur-xl border border-gray-700 rounded-2xl shadow-2xl flex flex-col overflow-hidden transition-all duration-300 transform origin-bottom-right z-50 ${
          isOpen ? 'scale-100 opacity-100 translate-y-0' : 'scale-95 opacity-0 pointer-events-none translate-y-8'
        }`}
      >
        {/* Header */}
        <div className="px-5 py-4 border-b border-gray-700 bg-dark-900/50 flex items-center gap-3">
          <div className="w-8 h-8 rounded-full bg-primary-500/20 border border-primary-500/30 flex items-center justify-center shrink-0">
            <Bot className="w-5 h-5 text-primary-400" />
          </div>
          <div className="flex-1">
            <h3 className="font-semibold text-gray-100 text-sm">Nexus AI Agent</h3>
            <p className="text-xs text-primary-400 flex items-center gap-1">
              <span className="w-1.5 h-1.5 rounded-full bg-primary-500 animate-pulse"></span>
              Online
            </p>
          </div>
        </div>

        {/* Messages Area */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4 scrollbar-thin scrollbar-thumb-gray-700 scrollbar-track-transparent">
          {messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex gap-3 max-w-[85%] ${msg.sender === 'user' ? 'ml-auto flex-row-reverse' : ''}`}
            >
              <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 mt-1 ${
                msg.sender === 'user' ? 'bg-emerald-500/20 border border-emerald-500/30 text-emerald-400' : 'bg-primary-500/20 border border-primary-500/30 text-primary-400'
              }`}>
                {msg.sender === 'user' ? <User className="w-4 h-4" /> : <Bot className="w-4 h-4" />}
              </div>
              <div
                className={`p-3 rounded-2xl text-sm whitespace-pre-wrap shadow-sm ${
                  msg.sender === 'user'
                    ? 'bg-emerald-600 text-white rounded-tr-none'
                    : 'bg-dark-700 border border-gray-600 text-gray-200 rounded-tl-none'
                }`}
              >
                {msg.text || (msg.sender === 'agent' && isTyping && <span className="animate-pulse">...</span>)}
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>

        {/* Input Area */}
        <div className="p-4 bg-dark-900/50 border-t border-gray-700">
          <form onSubmit={handleSubmit} className="flex gap-2">
            <input
              type="text"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={activeNodeId ? "Ask about this node..." : "Ask about the project..."}
              disabled={isTyping}
              className="flex-1 bg-dark-800 border border-gray-700 text-gray-200 text-sm rounded-xl px-4 py-2.5 focus:outline-none focus:border-primary-500 focus:ring-1 focus:ring-primary-500 disabled:opacity-50 transition-all"
            />
            <button
              type="submit"
              disabled={!input.trim() || isTyping}
              className="bg-primary-600 hover:bg-primary-500 text-white p-2.5 rounded-xl disabled:opacity-50 disabled:cursor-not-allowed transition-colors shrink-0 flex items-center justify-center"
            >
              {isTyping ? <Loader2 className="w-5 h-5 animate-spin" /> : <Send className="w-5 h-5" />}
            </button>
          </form>
        </div>
      </div>
    </>
  );
}
