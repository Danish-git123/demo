import React, { useMemo, useState, useEffect, useCallback } from 'react';
import ReactFlow, { Background, Controls, MiniMap, Handle, Position, useNodesState, useEdgesState, addEdge } from 'reactflow';
import 'reactflow/dist/style.css';
import dagre from 'dagre';

import { FileCode, Folder, Database, Layout, Code, Terminal, Box, Play, Layers, Info, X, Loader2 } from 'lucide-react';
import { useAuth } from '../hooks/useAuth.js';

// Custom Node Setup
const CustomCircleNode = ({ data }) => {
  return (
    <div 
      className={`relative group flex flex-col items-center justify-center w-28 h-28 rounded-[35%] border-2 backdrop-blur-md transition-all duration-300 shadow-xl cursor-pointer hover:-translate-y-1 ${data.colorClass}`}
      title={data.nodeObj.filePath}
    >
      <Handle type="target" position={Position.Top} className="opacity-0 group-hover:opacity-100 transition-opacity" />
      
      <div className="flex flex-col items-center justify-center p-2 text-center w-full h-full">
        <div className="mb-2 opacity-90 scale-125">{data.icon}</div>
        <h4 className="font-semibold text-xs leading-tight break-words px-1 line-clamp-2">
          {data.nodeObj.label || data.nodeObj.id.split('-')[0]}
        </h4>
      </div>

      <button 
        onClick={(e) => data.onExplainClick(e, data.nodeObj)}
        className="absolute -top-3 -right-3 p-2 bg-dark-800 rounded-full border border-gray-600 hover:bg-primary-600 hover:border-primary-400 text-gray-300 hover:text-white transition-all opacity-0 group-hover:opacity-100 shadow-lg z-10"
        title="Explain Node"
      >
        <Info className="w-4 h-4" />
      </button>

      <Handle type="source" position={Position.Bottom} className="opacity-0 group-hover:opacity-100 transition-opacity" />
    </div>
  );
};

const nodeTypes = {
  customCircle: CustomCircleNode,
};

// Layout Algorithm
const getLayoutedElements = (nodes, edges) => {
  const nodeWidth = 140;
  const nodeHeight = 140;

  // Group nodes by their defined architectural level
  const levels = {};
  nodes.forEach(node => {
    const level = node.data.level;
    if (!levels[level]) levels[level] = [];
    levels[level].push(node);
  });

  const maxNodesInLevel = Math.max(...Object.values(levels).map(arr => arr.length));
  const maxFullWidth = maxNodesInLevel * (nodeWidth + 60);
  const leftAlignX = -(maxFullWidth / 2) - 150;

  const newNodes = nodes.map((node) => {
    const level = node.data.level;
    const itemsInLevel = levels[level];
    
    // Sort items within level alphabetically by label to keep it neat
    itemsInLevel.sort((a, b) => {
      const labelA = a.data.nodeObj.label || '';
      const labelB = b.data.nodeObj.label || '';
      return labelA.localeCompare(labelB);
    });

    const index = itemsInLevel.findIndex(n => n.id === node.id);
    
    // Center the horizontal row based on how many nodes exist in this level
    const totalWidth = itemsInLevel.length * (nodeWidth + 60);
    const startX = -(totalWidth / 2) + (nodeWidth / 2);

    return {
      ...node,
      position: {
        x: startX + index * (nodeWidth + 60),
        y: level * (nodeHeight + 100),
      },
    };
  });

  // Inject level labels on the left side
  Object.keys(levels).forEach(levelStr => {
    const level = parseInt(levelStr);
    newNodes.push({
      id: `level-label-${level}`,
      type: 'default',
      data: { 
        label: <div className="text-gray-400 font-bold bg-dark-900 border border-gray-700 px-4 py-2 rounded-xl backdrop-blur-sm shadow-xl flex items-center justify-center min-w-[120px]">Level {level}</div> 
      },
      position: { 
        x: leftAlignX, 
        y: level * (nodeHeight + 100) + (nodeHeight / 2) - 20 
      },
      draggable: false,
      selectable: false,
      style: { border: 'none', background: 'transparent', boxShadow: 'none' }
    });
  });

  return { nodes: newNodes, edges };
};

export default function NodeViewer({ nodes: rawNodes }) {
  const { getToken } = useAuth();
  
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  const [modalState, setModalState] = useState({
    isOpen: false,
    node: null,
    explanation: '',
    isLoading: false,
    error: null
  });

  const getTypeColor = (type) => {
    const typeLower = type ? type.toLowerCase() : '';
    if (typeLower.includes('query') || typeLower.includes('db') || typeLower.includes('database')) return 'bg-blue-500/20 border-blue-500/50 text-blue-300';
    if (typeLower.includes('config') || typeLower.includes('property')) return 'bg-amber-500/20 border-amber-500/50 text-amber-300';
    if (typeLower.includes('component') || typeLower.includes('view') || typeLower.includes('ui')) return 'bg-emerald-500/20 border-emerald-500/50 text-emerald-300';
    if (typeLower.includes('service') || typeLower.includes('logic')) return 'bg-purple-500/20 border-purple-500/50 text-purple-300';
    if (typeLower.includes('controller') || typeLower.includes('route') || typeLower.includes('endpoint')) return 'bg-pink-500/20 border-pink-500/50 text-pink-300';
    if (typeLower.includes('model') || typeLower.includes('entity') || typeLower.includes('dto')) return 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300';
    if (typeLower.includes('folder') || typeLower.includes('dir')) return 'bg-gray-500/20 border-gray-500/50 text-gray-300';
    
    return 'bg-indigo-500/20 border-indigo-500/50 text-indigo-300';
  };

  const getIcon = (type) => {
    const typeLower = type ? type.toLowerCase() : '';
    if (typeLower.includes('db') || typeLower.includes('query')) return <Database className="w-5 h-5" />;
    if (typeLower.includes('ui') || typeLower.includes('view')) return <Layout className="w-5 h-5" />;
    if (typeLower.includes('service') || typeLower.includes('logic')) return <Terminal className="w-5 h-5" />;
    if (typeLower.includes('controller')) return <Play className="w-5 h-5" />;
    if (typeLower.includes('model') || typeLower.includes('dto')) return <Box className="w-5 h-5" />;
    if (typeLower.includes('folder') || typeLower.includes('dir')) return <Folder className="w-5 h-5" />;
    if (typeLower.includes('module')) return <Layers className="w-5 h-5" />;
    return <FileCode className="w-5 h-5" />;
  };

  const getNodeLevel = (node) => {
    const rawType = (node.nodeType || '').toLowerCase();
    const label = (node.label || '').toLowerCase();
    const path = (node.filePath || '').toLowerCase();
    
    const searchString = `${rawType} ${label} ${path}`;
    
    // Ensure accurate levels based on logical domain priority
    // Level 1 (Lowest/Closest to bottom): Database / Entities / Models
    if (searchString.includes('db') || searchString.includes('schema') || searchString.includes('migration') || searchString.includes('entity') || searchString.includes('model') || searchString.includes('dto')) {
      return 1;
    }
    // Level 2: Repository / DAO
    if (searchString.includes('repo') || searchString.includes('dao') || searchString.includes('data')) {
      return 2;
    }
    // Level 3: Services / Business Logic
    if (searchString.includes('service') || searchString.includes('logic') || searchString.includes('provider') || searchString.includes('handler') || searchString.includes('manager')) {
      return 3;
    }
    // Level 4: Controllers / API endpoints
    if (searchString.includes('controller') || searchString.includes('route') || searchString.includes('endpoint') || searchString.includes('resolver') || searchString.includes('api')) {
      return 4;
    }
    // Level 5 (Highest / Client-side): Frontend Components / Pages
    if (searchString.includes('component') || searchString.includes('view') || searchString.includes('page') || searchString.includes('ui') || searchString.includes('frontend') || searchString.includes('client') || searchString.includes('hook') || searchString.includes('app')) {
      return 5;
    }
    
    // Level 0: Catch-all (Config and structural modules)
    return 0;
  };

  const handleExplain = async (e, node) => {
    e.stopPropagation();
    setModalState({
      isOpen: true,
      node,
      explanation: '',
      isLoading: true,
      error: null
    });

    try {
      const token = await getToken();
      const response = await fetch(`/api/node/${node.id}`, {
        headers: {
          'Authorization': `Bearer ${token}`
        }
      });
      if (!response.ok) {
        throw new Error('Failed to fetch explanation');
      }
      const data = await response.json();
      setModalState(prev => ({
        ...prev,
        isLoading: false,
        explanation: data.aiExplanation || JSON.stringify(data)
      }));
    } catch (err) {
      setModalState(prev => ({
        ...prev,
        isLoading: false,
        error: 'Could not load explanation for this node.'
      }));
    }
  };

  useEffect(() => {
    if (!rawNodes || rawNodes.length === 0) return;

    // Separate domains (Backend / Frontend) to segregate them mathematically
    // The trick is to lay them out together but ensure styling/background implies separation
    // Dagre naturally separates disconnected subgraphs!
    
    // Convert to React Flow Nodes
    let constructedNodes = rawNodes.map((n) => ({
      id: n.id,
      type: 'customCircle',
      data: {
        nodeObj: n,
        icon: getIcon(n.nodeType),
        colorClass: getTypeColor(n.nodeType),
        onExplainClick: handleExplain,
        level: getNodeLevel(n),
      },
      position: { x: 0, y: 0 }, 
    }));

    // Construct Edges
    let constructedEdges = [];
    rawNodes.forEach(n => {
      if (n.dependancies && Array.isArray(n.dependancies)) {
        n.dependancies.forEach(depId => {
          // Avoid self-references just in case
          if (n.id !== depId) {
            constructedEdges.push({
              id: `e-${n.id}-${depId}`,
              source: n.id,
              target: depId,
              animated: true,
              style: { stroke: '#6366f1', strokeWidth: 2 },
            });
          }
        });
      }
    });

    // Apply Layout
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(
      constructedNodes,
      constructedEdges
    );

    setNodes(layoutedNodes);
    setEdges(layoutedEdges);
  }, [rawNodes]);

  if (!rawNodes || rawNodes.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center p-12 bg-dark-800 rounded-xl border border-gray-800 backdrop-blur-sm">
        <Database className="w-12 h-12 text-gray-600 mb-4" />
        <p className="text-gray-400">No nodes found in the structure.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4 animate-fade-in-up">
      <div className="w-full h-[650px] bg-dark-800 rounded-xl border border-gray-800 overflow-hidden shadow-inner flex flex-col relative">
        <div className="absolute top-2 left-4 z-10 flex gap-4 text-xs font-semibold uppercase text-gray-500">
           <span>Shift + Drag to Pan</span>
           <span>Scroll to Zoom</span>
        </div>
        
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          nodeTypes={nodeTypes}
          fitView
          fitViewOptions={{ padding: 0.2 }}
          minZoom={0.1}
          attributionPosition="bottom-right"
        >
          <Background color="#374151" gap={16} />
          <Controls className="fill-white !bg-dark-700 !border-gray-600 [&>button]:!border-gray-600 [&>button:hover]:!bg-dark-600 pt-2" />
          <MiniMap 
            nodeColor={(n) => {
              const colorClass = n.data?.colorClass || '';
              if(colorClass.includes('emerald')) return '#10b981';
              if(colorClass.includes('purple')) return '#8b5cf6';
              if(colorClass.includes('pink')) return '#ec4899';
              if(colorClass.includes('amber')) return '#f59e0b';
              if(colorClass.includes('blue')) return '#3b82f6';
              if(!colorClass && n.type === 'default') return '#1f2937';
              return '#6366f1';
            }}
            style={{ backgroundColor: '#111827', border: '1px solid #374151', borderRadius: '8px' }} 
            maskColor="rgba(0,0,0,0.5)"
          />
        </ReactFlow>
      </div>

      {modalState.isOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-fade-in-up">
          <div className="bg-dark-800 border border-gray-700 w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden flex flex-col">
            <div className="flex items-center justify-between px-6 py-4 border-b border-gray-700 bg-dark-900/50">
              <h3 className="text-lg font-semibold text-gray-200 flex items-center gap-2">
                 <Info className="w-5 h-5 text-primary-400" />
                 Node Explanation
              </h3>
              <button 
                onClick={() => setModalState(prev => ({ ...prev, isOpen: false }))}
                className="text-gray-400 hover:text-white transition-colors"
                title="Close"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-6">
              <div className="mb-4">
                 <span className="text-xs font-mono text-gray-500 bg-dark-900 px-2 py-1 rounded border border-gray-800">
                   {modalState.node?.label || modalState.node?.id}
                 </span>
              </div>
              
              {modalState.isLoading ? (
                <div className="flex flex-col items-center justify-center py-8 text-gray-400">
                  <Loader2 className="w-8 h-8 animate-spin mb-4 text-primary-500" />
                  <p>Analyzing node data...</p>
                </div>
              ) : modalState.error ? (
                <div className="text-red-400 bg-red-500/10 border border-red-500/20 p-4 rounded-lg mt-2">
                  {modalState.error}
                </div>
              ) : (
                <div className="prose prose-invert max-w-none text-sm text-gray-300 mt-2 max-h-[60vh] overflow-y-auto whitespace-pre-wrap">
                   {modalState.explanation}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
