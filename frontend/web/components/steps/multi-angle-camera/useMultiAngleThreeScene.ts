'use client'

import type { MutableRefObject,RefObject } from 'react'
import { useRef } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { NINE_GRID_SPHERE_CAMERA_POSITIONS } from '~/utils/nineGridCameraAngles'

/** 原 three.js 场景 let 变量集合：非渲染状态，统一放入可变对象 ref */
export type ThreeSceneVars = {
  renderer: THREE.WebGLRenderer | null
  scene: THREE.Scene | null
  viewCamera: THREE.PerspectiveCamera | null
  orbitControls: OrbitControls | null
  frameId: number
  rigGroup: THREE.Group | null
  sphereGroup: THREE.Group | null
  sphereLatLinesGroup: THREE.Group | null
  sphereLonLinesGroup: THREE.Group | null
  markerGroup: THREE.Group | null
  nineGridMarkersGroup: THREE.Group | null
  nineGridLinesGroup: THREE.Group | null
  beamMesh: THREE.Mesh | null
  texturedSphereMesh: THREE.Mesh | null
  texturedSphereMaterial: THREE.ShaderMaterial | null
  currentSphereTexture: THREE.Texture | null
  resizeObserver: ResizeObserver | null
  objectUrlToRevoke: string
}

function createThreeSceneVars(): ThreeSceneVars {
  return {
    renderer: null,
    scene: null,
    viewCamera: null,
    orbitControls: null,
    frameId: 0,
    rigGroup: null,
    sphereGroup: null,
    sphereLatLinesGroup: null,
    sphereLonLinesGroup: null,
    markerGroup: null,
    nineGridMarkersGroup: null,
    nineGridLinesGroup: null,
    beamMesh: null,
    texturedSphereMesh: null,
    texturedSphereMaterial: null,
    currentSphereTexture: null,
    resizeObserver: null,
    objectUrlToRevoke: ''
  }
}

const createCameraMarkerMesh = () => {
  const bodyGeo = new THREE.BoxGeometry(0.42, 0.42, 0.42)
  const bodyMat = new THREE.MeshPhongMaterial({
    color: '#16b955',
    depthTest: false,
    depthWrite: false
  })
  const lensBarrelGeo = new THREE.CylinderGeometry(0.085, 0.085, 0.14, 28)
  lensBarrelGeo.rotateX(Math.PI / 2)
  const lensBarrelMat = new THREE.MeshPhongMaterial({
    color: '#5adf8b',
    depthTest: false,
    depthWrite: false
  })
  const lensRingGeo = new THREE.TorusGeometry(0.1, 0.018, 18, 40)
  const lensRingMat = new THREE.MeshPhongMaterial({
    color: '#7cf5a5',
    emissive: '#2bcf66',
    emissiveIntensity: 0.45,
    depthTest: false,
    depthWrite: false
  })
  const lensCenterGeo = new THREE.CylinderGeometry(0.034, 0.034, 0.02, 20)
  lensCenterGeo.rotateX(Math.PI / 2)
  const lensCenterMat = new THREE.MeshPhongMaterial({
    color: '#0f7f3e',
    depthTest: false,
    depthWrite: false
  })
  const redDotGeo = new THREE.SphereGeometry(0.02, 16, 12)
  const redDotMat = new THREE.MeshBasicMaterial({ color: '#ff3b58' })
  const markerBody = new THREE.Mesh(bodyGeo, bodyMat)
  const markerLensBarrel = new THREE.Mesh(lensBarrelGeo, lensBarrelMat)
  const markerLensRing = new THREE.Mesh(lensRingGeo, lensRingMat)
  const markerLensCenter = new THREE.Mesh(lensCenterGeo, lensCenterMat)
  const markerRedDot = new THREE.Mesh(redDotGeo, redDotMat)
  markerLensBarrel.position.set(0, 0, -0.28)
  markerLensRing.position.set(0, 0, -0.36)
  markerLensCenter.position.set(0, 0, -0.37)
  markerRedDot.position.set(-0.13, 0.03, -0.12)
  ;[markerBody, markerLensBarrel, markerLensRing, markerLensCenter, markerRedDot].forEach((m) => {
    m.renderOrder = 20
  })
  const group = new THREE.Group()
  group.add(markerBody, markerLensBarrel, markerLensRing, markerLensCenter, markerRedDot)
  return group
}

export interface UseMultiAngleThreeSceneParams {
  canvasRootRef: RefObject<HTMLDivElement | null>
  fixedNineGridRef: MutableRefObject<boolean>
  horizontalRotationRef: MutableRefObject<number>
  verticalAngleRef: MutableRefObject<number>
  focalLengthRef: MutableRefObject<number>
  hasInteractedRef: MutableRefObject<boolean>
  activeImageUrlRef: MutableRefObject<string>
  setHasInteracted: (v: boolean) => void
  setTextureReady: (v: boolean) => void
}

/** 多机位弹窗 three.js 场景引擎：init/destroy/贴图/机位更新（从 MultiAngleCameraModal 拆出，逻辑原样） */
export function useMultiAngleThreeScene({
  canvasRootRef,
  fixedNineGridRef,
  horizontalRotationRef,
  verticalAngleRef,
  focalLengthRef,
  hasInteractedRef,
  activeImageUrlRef,
  setHasInteracted,
  setTextureReady
}: UseMultiAngleThreeSceneParams) {
  const varsRef = useRef<ThreeSceneVars>(createThreeSceneVars())

  const applyTexture = (url: string) => {
    const vars = varsRef.current
    if (!vars.scene || !vars.texturedSphereMaterial) return
    const uniforms = vars.texturedSphereMaterial.uniforms as Record<string, { value: any }>
    if (vars.currentSphereTexture) {
      vars.currentSphereTexture.dispose()
      vars.currentSphereTexture = null
    }
    if (!url) {
      uniforms.uMap.value = null
      uniforms.uHasMap.value = 0
      setTextureReady(false)
      return
    }
    setTextureReady(false)
    const loader = new THREE.TextureLoader()
    loader.setCrossOrigin('anonymous')
    loader.load(
      url,
      (tex) => {
        tex.colorSpace = THREE.SRGBColorSpace
        tex.wrapS = THREE.ClampToEdgeWrapping
        tex.wrapT = THREE.ClampToEdgeWrapping
        vars.currentSphereTexture = tex
        uniforms.uMap.value = tex
        uniforms.uHasMap.value = 1
        setTextureReady(true)
      },
      undefined,
      () => {
        uniforms.uMap.value = null
        uniforms.uHasMap.value = 0
        setTextureReady(false)
      }
    )
  }

  const updateCameraRig = () => {
    const vars = varsRef.current
    if (!vars.rigGroup || !vars.viewCamera || !vars.texturedSphereMesh || !vars.sphereGroup) return

    if (fixedNineGridRef.current) {
      vars.texturedSphereMesh.scale.set(1, 1, 1)
      vars.sphereGroup.scale.set(1, 1, 1)
      vars.rigGroup.rotation.set(0, 0, 0)
      if (vars.markerGroup) vars.markerGroup.visible = false
      if (vars.nineGridMarkersGroup) vars.nineGridMarkersGroup.visible = true
      if (vars.nineGridLinesGroup) vars.nineGridLinesGroup.visible = true
      if (vars.sphereLatLinesGroup) vars.sphereLatLinesGroup.visible = true
      if (vars.sphereLonLinesGroup) vars.sphereLonLinesGroup.visible = true
      vars.viewCamera.fov = 48
      vars.viewCamera.updateProjectionMatrix()
      return
    }

    if (!vars.markerGroup) return
    const radius = 2.95
    const theta = THREE.MathUtils.degToRad(horizontalRotationRef.current)
    const phi = THREE.MathUtils.degToRad(verticalAngleRef.current)
    const isFlatPreview =
      !hasInteractedRef.current &&
      Math.abs(horizontalRotationRef.current) < 0.1 &&
      Math.abs(verticalAngleRef.current) < 0.1
    vars.markerGroup.position.set(0, 0, radius)
    vars.markerGroup.lookAt(0, 0, 0)
    vars.markerGroup.rotateY(Math.PI)

    if (isFlatPreview) {
      vars.texturedSphereMesh.scale.set(1, 1, 0.02)
      vars.sphereGroup.scale.set(1, 1, 0.02)
      vars.markerGroup.visible = false
      vars.rigGroup.rotation.set(0, 0, 0)
      if (vars.texturedSphereMaterial) {
        const uniforms = vars.texturedSphereMaterial.uniforms as Record<string, { value: THREE.Vector2 }>
        uniforms.uRectMin.value.set(0.08, 0.12)
        uniforms.uRectMax.value.set(0.92, 0.88)
      }
    } else {
      vars.texturedSphereMesh.scale.set(1, 1, 1)
      vars.sphereGroup.scale.set(1, 1, 1)
      vars.markerGroup.visible = true
      vars.rigGroup.rotation.set(-phi * 0.65, theta, 0)
      if (vars.texturedSphereMaterial) {
        const uniforms = vars.texturedSphereMaterial.uniforms as Record<string, { value: THREE.Vector2 }>
        uniforms.uRectMin.value.set(0.35, 0.37)
        uniforms.uRectMax.value.set(0.65, 0.63)
      }
    }

    if (vars.sphereLatLinesGroup) vars.sphereLatLinesGroup.visible = true
    if (vars.sphereLonLinesGroup) vars.sphereLonLinesGroup.visible = true

    vars.viewCamera.fov = THREE.MathUtils.mapLinear(focalLengthRef.current, 0, 10, 50, 28)
    vars.viewCamera.updateProjectionMatrix()
    if (vars.beamMesh) {
      vars.beamMesh.visible = Math.abs(verticalAngleRef.current) > 0.1 || hasInteractedRef.current
    }
  }

  const buildNineGridMarkers = () => {
    const markers = new THREE.Group()
    const lines = new THREE.Group()
    const lineMat = new THREE.LineBasicMaterial({
      color: '#6a7088',
      transparent: true,
      opacity: 0.45
    })
    const radius = 2.95
    for (const pos of NINE_GRID_SPHERE_CAMERA_POSITIONS) {
      const yaw = THREE.MathUtils.degToRad(pos.yaw)
      const pitch = THREE.MathUtils.degToRad(pos.pitch)
      const x = radius * Math.cos(pitch) * Math.sin(yaw)
      const y = radius * Math.sin(pitch)
      const z = radius * Math.cos(pitch) * Math.cos(yaw)
      const marker = createCameraMarkerMesh()
      marker.position.set(x, y, z)
      marker.lookAt(0, 0, 0)
      marker.rotateY(Math.PI)
      markers.add(marker)
      const lineGeo = new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(x, y, z),
        new THREE.Vector3(0, 0, 0)
      ])
      lines.add(new THREE.Line(lineGeo, lineMat))
    }
    return { markers, lines }
  }

  const resizeRenderer = () => {
    const vars = varsRef.current
    if (!vars.renderer || !vars.viewCamera || !canvasRootRef.current) return
    const { clientWidth, clientHeight } = canvasRootRef.current
    if (!clientWidth || !clientHeight) return
    vars.renderer.setSize(clientWidth, clientHeight, true)
    vars.viewCamera.aspect = clientWidth / clientHeight
    vars.viewCamera.updateProjectionMatrix()
  }

  const animate = () => {
    const vars = varsRef.current
    if (!vars.renderer || !vars.scene || !vars.viewCamera) return
    vars.frameId = requestAnimationFrame(animate)
    vars.orbitControls?.update()
    vars.renderer.render(vars.scene, vars.viewCamera)
  }

  const initThreeScene = async () => {
    // 原 nextTick：等待弹窗内容挂载完成
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    const vars = varsRef.current
    if (!canvasRootRef.current || vars.renderer) return

    const root = canvasRootRef.current
    vars.scene = new THREE.Scene()
    vars.scene.background = new THREE.Color('#121212')

    vars.viewCamera = new THREE.PerspectiveCamera(48, 1, 0.1, 100)
    vars.viewCamera.position.set(0, 0, 6.8)

    vars.renderer = new THREE.WebGLRenderer({ antialias: true })
    vars.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    vars.renderer.outputColorSpace = THREE.SRGBColorSpace
    root.appendChild(vars.renderer.domElement)

    vars.orbitControls = new OrbitControls(vars.viewCamera, vars.renderer.domElement)
    vars.orbitControls.enablePan = false
    vars.orbitControls.enableDamping = true
    vars.orbitControls.enabled = true
    vars.orbitControls.enableRotate = !fixedNineGridRef.current
    vars.orbitControls.enableZoom = !fixedNineGridRef.current
    vars.orbitControls.minDistance = 3.8
    vars.orbitControls.maxDistance = 9
    if (!fixedNineGridRef.current) {
      vars.renderer.domElement.addEventListener('pointerdown', () => {
        setHasInteracted(true)
        updateCameraRig()
      })
    }

    const ambient = new THREE.AmbientLight('#9bb8ff', 0.5)
    vars.scene.add(ambient)
    const dirLight = new THREE.DirectionalLight('#ffffff', 0.8)
    dirLight.position.set(5, 5, 6)
    vars.scene.add(dirLight)

    const buildLatLonSphere = () => {
      const radius = 2.45
      const latCount = 10
      const lonCount = 14
      const segments = 100
      const material = new THREE.LineBasicMaterial({
        color: '#4c5366',
        transparent: true,
        opacity: 0.24
      })
      const group = new THREE.Group()
      const latGroup = new THREE.Group()
      const lonGroup = new THREE.Group()

      // horizontal circles (latitude)
      for (let i = 1; i <= latCount; i++) {
        const phi = (i / (latCount + 1)) * Math.PI - Math.PI / 2
        const ringRadius = Math.cos(phi) * radius
        const y = Math.sin(phi) * radius
        const points: THREE.Vector3[] = []
        for (let s = 0; s <= segments; s++) {
          const t = (s / segments) * Math.PI * 2
          points.push(new THREE.Vector3(Math.cos(t) * ringRadius, y, Math.sin(t) * ringRadius))
        }
        const geo = new THREE.BufferGeometry().setFromPoints(points)
        latGroup.add(new THREE.Line(geo, material))
      }

      // vertical circles (longitude)
      for (let i = 0; i < lonCount; i++) {
        const theta = (i / lonCount) * Math.PI * 2
        const points: THREE.Vector3[] = []
        for (let s = 0; s <= segments; s++) {
          const v = (s / segments) * Math.PI - Math.PI / 2
          const r = Math.cos(v) * radius
          const y = Math.sin(v) * radius
          points.push(new THREE.Vector3(Math.cos(theta) * r, y, Math.sin(theta) * r))
        }
        const geo = new THREE.BufferGeometry().setFromPoints(points)
        lonGroup.add(new THREE.Line(geo, material))
      }
      vars.sphereLatLinesGroup = latGroup
      vars.sphereLonLinesGroup = lonGroup
      group.add(latGroup)
      group.add(lonGroup)
      return group
    }
    vars.sphereGroup = buildLatLonSphere()

    const sphereGeo = new THREE.SphereGeometry(2.42, 80, 80)
    vars.texturedSphereMaterial = new THREE.ShaderMaterial({
      transparent: true,
      depthWrite: false,
      side: THREE.DoubleSide,
      uniforms: {
        uMap: { value: null },
        uHasMap: { value: 0 },
        uRectMin: { value: new THREE.Vector2(0.35, 0.37) },
        uRectMax: { value: new THREE.Vector2(0.65, 0.63) },
        uOuterColor: { value: new THREE.Color('#3a4155') },
        uOuterAlpha: { value: 0 }
      },
      vertexShader: `
        varying vec2 vUv;
        void main() {
          vUv = uv;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform sampler2D uMap;
        uniform float uHasMap;
        uniform vec2 uRectMin;
        uniform vec2 uRectMax;
        uniform vec3 uOuterColor;
        uniform float uOuterAlpha;
        varying vec2 vUv;
        void main() {
          float inRectX = step(uRectMin.x, vUv.x) * step(vUv.x, uRectMax.x);
          float inRectY = step(uRectMin.y, vUv.y) * step(vUv.y, uRectMax.y);
          float mask = inRectX * inRectY * uHasMap;
          vec3 texColor = texture2D(uMap, vUv).rgb;
          vec3 finalColor = mix(uOuterColor, texColor, mask);
          float finalAlpha = mix(uOuterAlpha, 1.0, mask);
          gl_FragColor = vec4(finalColor, finalAlpha);
        }
      `
    })
    vars.texturedSphereMesh = new THREE.Mesh(sphereGeo, vars.texturedSphereMaterial)
    vars.texturedSphereMesh.renderOrder = 5

    vars.markerGroup = createCameraMarkerMesh()
    const beamLength = 1.35
    const beamGeo = new THREE.CylinderGeometry(0.02, 0.22, beamLength, 28, 1, true)
    beamGeo.translate(0, -beamLength / 2, 0)
    beamGeo.rotateX(-Math.PI / 2)
    const beamMat = new THREE.MeshBasicMaterial({
      color: '#f2f6ff',
      transparent: true,
      opacity: 0.2,
      side: THREE.DoubleSide,
      depthTest: false,
      depthWrite: false
    })
    vars.beamMesh = new THREE.Mesh(beamGeo, beamMat)
    vars.beamMesh.position.set(0, 0, -0.37)
    vars.beamMesh.rotation.y = Math.PI
    vars.beamMesh.renderOrder = 15
    vars.beamMesh.visible = false
    vars.markerGroup.add(vars.beamMesh)

    if (fixedNineGridRef.current) {
      const built = buildNineGridMarkers()
      vars.nineGridMarkersGroup = built.markers
      vars.nineGridLinesGroup = built.lines
    }

    vars.rigGroup = new THREE.Group()
    vars.rigGroup.add(vars.texturedSphereMesh)
    vars.rigGroup.add(vars.sphereGroup)
    if (fixedNineGridRef.current && vars.nineGridMarkersGroup && vars.nineGridLinesGroup) {
      vars.rigGroup.add(vars.nineGridLinesGroup)
      vars.rigGroup.add(vars.nineGridMarkersGroup)
      vars.markerGroup.visible = false
    } else {
      vars.rigGroup.add(vars.markerGroup)
    }
    vars.scene.add(vars.rigGroup)
    updateCameraRig()

    resizeRenderer()
    vars.resizeObserver = new ResizeObserver(resizeRenderer)
    vars.resizeObserver.observe(root)
    applyTexture(activeImageUrlRef.current)
    animate()
  }

  const destroyThreeScene = () => {
    const vars = varsRef.current
    if (vars.frameId) cancelAnimationFrame(vars.frameId)
    vars.frameId = 0
    vars.resizeObserver?.disconnect()
    vars.resizeObserver = null
    vars.orbitControls?.dispose()
    vars.orbitControls = null
    vars.renderer?.dispose()
    if (vars.renderer?.domElement?.parentNode) {
      vars.renderer.domElement.parentNode.removeChild(vars.renderer.domElement)
    }
    vars.scene = null
    vars.renderer = null
    vars.viewCamera = null
    vars.sphereGroup = null
    vars.sphereLatLinesGroup = null
    vars.sphereLonLinesGroup = null
    vars.rigGroup = null
    vars.markerGroup = null
    vars.nineGridMarkersGroup = null
    vars.nineGridLinesGroup = null
    vars.beamMesh = null
    if (vars.currentSphereTexture) {
      vars.currentSphereTexture.dispose()
      vars.currentSphereTexture = null
    }
    vars.texturedSphereMaterial?.dispose()
    vars.texturedSphereMaterial = null
    vars.texturedSphereMesh = null
  }

  return {
    varsRef,
    applyTexture,
    updateCameraRig,
    resizeRenderer,
    initThreeScene,
    destroyThreeScene
  }
}
