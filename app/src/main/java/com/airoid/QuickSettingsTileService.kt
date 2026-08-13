package com.airoid

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.airoid.service.AirPlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 빠른 설정(Quick Settings) 타일.
 * - 미러링 중이면 ACTIVE, 아니면 INACTIVE — AirPlayService의 mirroringActive
 *   플로우를 수신해 패널이 열려 있는 동안(lisening) 상태를 갱신한다.
 * - 탭하면 앱(MainActivity)을 실행한다. 시스템 API 제약상 프로그래밍 방식으로
 *   타일을 추가할 수 없으므로, 사용자가 빠른 설정 편집(+ 버튼)에서 직접 추가해야
 *   표시된다.
 *
 * 콜드 상태(프로세스 freeze)에서 액티비티를 직접 시작하면 백그라운드 시작
 * 제한(BAL)에 막힐 수 있다(타일 클릭 15초 임시 허용리스트가 freeze 해제 지연으로
 * 만료). 수신 서비스(FGS) 시작은 타일 클릭 허용리스트로 보장되므로 서비스를 먼저
 * 시작해 앱을 활성 상태로 만든 뒤 액티비티를 실행한다. 잠금 상태에서는 해제 후
 * 실행한다(unlockAndRun).
 */
class QuickSettingsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as AirPlayService.LocalBinder).service
            collectJob = scope.launch {
                svc.mirroringActive.collect { active ->
                    setTileState(active)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            collectJob?.cancel()
            collectJob = null
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // 초기값 — 바인드가 풀리면 서비스의 현재 상태로 즉시 갱신된다
        setTileState(false)
        bound = bindService(
            Intent(this, AirPlayService::class.java),
            conn,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStopListening() {
        super.onStopListening()
        collectJob?.cancel()
        collectJob = null
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        collectJob = null
        if (bound) {
            runCatching { unbindService(conn) }
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val launch = {
            try {
                // 수신 서비스(FGS) 시작 — 타일 클릭 임시 허용리스트로 보장된다.
                // 콜드 프로세스에서도 앱을 활성 상태로 만들어 아래 실행이 BAL에 안 막힌다.
                runCatching {
                    startService(
                        Intent(this, AirPlayService::class.java)
                            .setAction(AirPlayService.ACTION_START_SERVER)
                    )
                }
                // 앱 실행 + 빠른 설정 패널 접기.
                // API 35+에서 Intent 버전은 UnsupportedOperationException을 던진다 —
                // 시스템이 실행을 보장하는 PendingIntent 버전을 사용한다.
                // CLEAR_TOP|SINGLE_TOP: 이미 떠 있는 MainActivity를 재사용해 앞으로
                // 가져온다 — 새 인스턴스를 쌓으면 transition이 0에서 시작해 진입
                // 애니메이션이 다시 재생되어 "새로 뜨는" 것처럼 보인다.
                val intent = Intent(this, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    val pi = PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    startActivityAndCollapse(pi)
                } else {
                    startActivityAndCollapse(intent)
                }
                Log.i(TAG, "Launched MainActivity from QS tile")
            } catch (e: Exception) {
                Log.e(TAG, "QS tile launch failed", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLocked()) {
            unlockAndRun { launch() }
        } else {
            launch()
        }
    }

    private fun setTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.updateTile()
        Log.i(TAG, "Tile state: ${if (active) "ACTIVE" else "INACTIVE"}")
    }

    companion object {
        private const val TAG = "QuickSettingsTile"
    }
}
