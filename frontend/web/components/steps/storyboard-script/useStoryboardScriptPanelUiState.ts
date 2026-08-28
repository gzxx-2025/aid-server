'use client'

import { useEffect, useRef, useState } from 'react'

export function useStoryboardScriptPanelUiState() {
  const [isShotDragging, setIsShotDragging] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editingTitle, setEditingTitle] = useState('')
  const editingTitleRef = useRef(editingTitle)
  editingTitleRef.current = editingTitle
  const [batchDeleteSubmitting, setBatchDeleteSubmitting] = useState(false)
  const batchDeleteSubmittingRef = useRef(batchDeleteSubmitting)
  batchDeleteSubmittingRef.current = batchDeleteSubmitting
  const insertLeaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [activeInsertSlot, setActiveInsertSlot] = useState<number | null>(null)

  function onShotDragStart() {
    setIsShotDragging(true)
  }

  function onShotDragEnd() {
    setIsShotDragging(false)
  }

  function onInsertSlotEnter(index: number) {
    if (insertLeaveTimerRef.current) clearTimeout(insertLeaveTimerRef.current)
    insertLeaveTimerRef.current = null
    setActiveInsertSlot(index)
  }

  function onInsertSlotLeave() {
    insertLeaveTimerRef.current = setTimeout(() => {
      setActiveInsertSlot(null)
      insertLeaveTimerRef.current = null
    }, 180)
  }

  function clearInsertSlotImmediate() {
    if (insertLeaveTimerRef.current) clearTimeout(insertLeaveTimerRef.current)
    insertLeaveTimerRef.current = null
    setActiveInsertSlot(null)
  }

  useEffect(
    () => () => {
      if (insertLeaveTimerRef.current) clearTimeout(insertLeaveTimerRef.current)
    },
    []
  )

  return {
    isShotDragging,
    editingId,
    setEditingId,
    editingTitle,
    setEditingTitle,
    editingTitleRef,
    batchDeleteSubmitting,
    setBatchDeleteSubmitting,
    batchDeleteSubmittingRef,
    activeInsertSlot,
    onShotDragStart,
    onShotDragEnd,
    onInsertSlotEnter,
    onInsertSlotLeave,
    clearInsertSlotImmediate
  }
}
