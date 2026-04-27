import { useEffect, useState } from 'react'
import { supabase } from '../lib/supabaseClient'

export function useAuth() {
  const [user, setUser] = useState(null)
  const [session, setSession] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    supabase.auth.getSession().then(({ data: { session } }) => {
      setSession(session)
      setUser(session?.user ?? null)
      setLoading(false)
    })

    const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, session) => {
      setSession(session)
      setUser(session?.user ?? null)
      setLoading(false)
    })

    return () => subscription.unsubscribe()
  }, [])

  const signUp = async (email, password) => {
    const { data, error } = await supabase.auth.signUp({ email, password })
    return { data, error }
  }

  const signIn = async (email, password) => {
    sessionStorage.removeItem('nexus_githubUrl')
    sessionStorage.removeItem('nexus_parseResult')
    sessionStorage.removeItem('nexus_chat_isOpen')
    sessionStorage.removeItem('nexus_chat_messages')
    sessionStorage.removeItem('nexus_chat_conversationId')
    const { data, error } = await supabase.auth.signInWithPassword({ email, password })
    return { data, error }
  }

  const signOut = async () => {
    sessionStorage.removeItem('nexus_githubUrl')
    sessionStorage.removeItem('nexus_parseResult')
    sessionStorage.removeItem('nexus_chat_isOpen')
    sessionStorage.removeItem('nexus_chat_messages')
    sessionStorage.removeItem('nexus_chat_conversationId')
    const { error } = await supabase.auth.signOut()
    return { error }
  }

  const getToken = async () => {
    const { data: { session } } = await supabase.auth.getSession()
    return session?.access_token ?? null
  }

  return { user, session, loading, signUp, signIn, signOut, getToken }
}