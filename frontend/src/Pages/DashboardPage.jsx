import React, { useState } from 'react';
import { Search, GitBranch, LayoutGrid, Cpu, Code2, AlertCircle } from 'lucide-react';
import DynamicLoader from '../components/DynamicLoader.jsx';
import NodeViewer from '../components/NodeViewer.jsx';
import AgentChatWindow from '../components/AgentChatWindow.jsx';
import { useAuth } from '../hooks/useAuth.js';

export default function DashboardPage() {
  const [githubUrl, setGithubUrl] = useState(() => {
    const saved = sessionStorage.getItem('nexus_githubUrl');
    sessionStorage.removeItem('nexus_githubUrl');
    return saved || '';
  });
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const [parseResult, setParseResult] = useState(() => {
    const saved = sessionStorage.getItem('nexus_parseResult');
    sessionStorage.removeItem('nexus_parseResult');
    if (saved) {
      try { return JSON.parse(saved); } catch (e) { return null; }
    }
    return null;
  });

  const { getToken } = useAuth();

  React.useEffect(() => {
    const handleBeforeUnload = () => {
      if (parseResult) {
        sessionStorage.setItem('nexus_parseResult', JSON.stringify(parseResult));
        sessionStorage.setItem('nexus_githubUrl', githubUrl);
      }
    };
    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [parseResult, githubUrl]);

  const handleParse = async (e) => {
    e.preventDefault();
    if (!githubUrl || !githubUrl.includes('github.com')) {
      setError('Please enter a valid GitHub repository URL.');
      return;
    }

    setIsLoading(true);
    setError(null);
    setParseResult(null);

    try {
      const token = await getToken();
      
      // Use Vite proxy to avoid CORS preflight (ERR_ABORTED) issues on OPTIONS requests
      const apiUrl = '/api/parse'; 
      
      const response = await fetch(apiUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ githuburl: githubUrl }),
      });

      if (!response.ok) {
        throw new Error(`Server returned ${response.status}: ${response.statusText}`);
      }

      const data = await response.json();
      setParseResult(data);
    } catch (err) {
      console.error(err);
      setError('Failed to parse repository. Ensure the backend is running and the URL is correct.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-dark-900 text-gray-200 overflow-hidden relative">
      {/* Background ambient light */}
      <div className="absolute top-0 left-1/4 w-96 h-96 bg-primary-600 rounded-full mix-blend-multiply filter blur-[128px] opacity-20 animate-pulse-slow pointer-events-none"></div>
      <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-emerald-600 rounded-full mix-blend-multiply filter blur-[128px] opacity-10 animate-pulse-slow pointer-events-none delay-1000"></div>

      {/* Header */}
      <header className="border-b border-gray-800 bg-dark-900/50 backdrop-blur-md sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-primary-500/20 border border-primary-500/30 flex items-center justify-center">
              <Cpu className="w-5 h-5 text-primary-400" />
            </div>
            <h1 className="text-xl font-bold bg-clip-text text-transparent bg-gradient-to-r from-primary-400 to-accent-400">
              Nexus Parser
            </h1>
          </div>
          <div className="flex items-center gap-4">
            <div className="text-sm text-gray-400 flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
              System Active
            </div>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 relative z-10">
        
        {/* Input Section */}
        <div className="mb-12 max-w-2xl mx-auto animate-fade-in-up">
          <div className="bg-dark-800/80 backdrop-blur-xl border border-gray-800 rounded-2xl p-6 shadow-2xl">
            <h2 className="text-2xl font-bold text-white mb-2 text-center">Analyze Repository</h2>
            <p className="text-gray-400 text-center mb-6 text-sm">
              Enter a GitHub URL to map its structure and identify system nodes.
            </p>
            
            <form onSubmit={handleParse} className="relative">
              <div className="flex items-center bg-dark-900 border border-gray-700 focus-within:border-primary-500 focus-within:ring-1 focus-within:ring-primary-500 rounded-xl overflow-hidden transition-all duration-300">
                <div className="pl-4 text-gray-500">
                  <GitBranch className="w-5 h-5" />
                </div>
                <input 
                  type="url" 
                  value={githubUrl}
                  onChange={(e) => setGithubUrl(e.target.value)}
                  placeholder="https://github.com/user/repository"
                  className="flex-1 bg-transparent border-none py-4 px-4 text-gray-200 placeholder-gray-600 focus:outline-none"
                  disabled={isLoading}
                />
                <button 
                  type="submit"
                  disabled={isLoading || !githubUrl}
                  className="bg-primary-600 hover:bg-primary-500 text-white px-6 py-4 font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                >
                  <Search className="w-4 h-4" />
                  <span className="hidden sm:inline">Parse</span>
                </button>
              </div>
            </form>

            {error && (
              <div className="mt-4 p-4 rounded-lg bg-red-500/10 border border-red-500/20 flex items-start gap-3 text-red-400 text-sm animate-fade-in-up">
                <AlertCircle className="w-5 h-5 shrink-0" />
                <p>{error}</p>
              </div>
            )}
          </div>
        </div>

        {/* Results Area */}
        <div className="min-h-[400px] flex flex-col">
          {isLoading && (
            <div className="flex-1 flex items-center justify-center py-20">
              <DynamicLoader />
            </div>
          )}

          {!isLoading && parseResult && (
            <div className="animate-fade-in-up space-y-6">
              <div className="flex items-center justify-between border-b border-gray-800 pb-4">
                <div>
                  <h2 className="text-2xl font-bold text-white flex items-center gap-2">
                    <LayoutGrid className="text-primary-400" />
                    Structure Map
                  </h2>
                  <p className="text-gray-400 mt-1">
                    Showing {parseResult.nodes?.length || 0} nodes for <span className="text-gray-200 font-mono">{parseResult.repoName || githubUrl.split('/').pop()}</span>
                  </p>
                </div>
                {parseResult.detectedStack && (
                  <div className="px-4 py-2 rounded-full bg-dark-800 border border-gray-700 text-sm font-medium flex items-center gap-2">
                    <Code2 className="w-4 h-4 text-emerald-400" />
                    {parseResult.detectedStack}
                  </div>
                )}
              </div>
              
              <NodeViewer nodes={parseResult.nodes || []} />
            </div>
          )}

          {!isLoading && !parseResult && !error && (
            <div className="flex-1 flex flex-col items-center justify-center py-20 opacity-30 text-center">
              <LayoutGrid className="w-16 h-16 text-gray-500 mb-4" />
              <p className="text-gray-400 max-w-md">
                Results will appear here. The structure map groups files and components by their determined architecture levels.
              </p>
            </div>
          )}
        </div>
      </main>

      {/* Agent Chat Window */}
      {parseResult && (
        <AgentChatWindow 
          projectId={parseResult.projectId} 
          detectedFramework={parseResult.detectedStack} 
        />
      )}
    </div>
  );
}
