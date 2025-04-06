package com.example.myapplication
// 다른 화면(FootActivity)로 이동 하려고 쓸 Intent 가져 오기
import android.content.Intent
// UI 정렬 및 단위 관련 import
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
// Jetpack Compose 에서 화면 구성에 필요한 요소들
import androidx.compose.foundation.layout.*       // Column, Row, padding 등
import androidx.compose.material3.*             // Scaffold, Button, Text 등
import androidx.compose.runtime.Composable       // @Composable 함수 작성용
import androidx.compose.ui.Modifier              // Modifier 로 크기/정렬 조절
import androidx.compose.ui.tooling.preview.Preview // 미리 보기 기능
import com.example.myapplication.ui.theme.MyApplicationTheme // 테마 설정

// ✅ 앱의 첫 화면(메인 화면)을 담당 하는 클래스
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 상단 상태바/하단 네비게이션바를 콘텐츠와 자연 스럽게 연결
        enableEdgeToEdge()
        // 앱 UI 그리는 영역 (Jetpack Compose)
        setContent {
            // 우리 앱에서 정의한 테마 적용
            MyApplicationTheme {
                // 메인 화면을 그리는 Composable 함수 호출
                MainScreen(
                    onStartFootActivity = {
                        // 버튼 클릭 시 실행될 코드
                        val intent = Intent(this, FootActivity::class.java)
                        startActivity(intent) // FootActivity 실행!
                    }
                )
            }
        }
    }
}

// ✅ 실제 메인 화면을 구성 하는 Composable 함수
@Composable
fun MainScreen(onStartFootActivity: () -> Unit) {
    // Scaffold: 기본 화면 구조 제공 (TopAppBar, FAB 등 넣을 수 있음)
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        // 화면 중앙에 정렬된 세로(Column) 레이 아웃
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)   // Scaffold가 남긴 여백
                .padding(32.dp),         // 전체 여백
            horizontalAlignment = Alignment.CenterHorizontally, // 가로 정렬
            verticalArrangement = Arrangement.Center            // 세로 정렬
        ) {
            // 👉 [족압 측정] 버튼
            Button(onClick = onStartFootActivity) {
                Text("족압 측정") // 버튼 안의 텍스트
            }
            Spacer(modifier = Modifier.height(16.dp)) // 버튼 사이 간격
            // 👉 [다른 기능] 버튼 (기능은 아직 없음)
            Button(onClick = {
                // 추후 다른 화면 으로 연결할 수 있음
            }) {
                Text("다른 기능")
            }
        }
    }
}

// ✅ 미리 보기용 Preview 함수 (앱 실행 안 해도 디자인 확인 가능)
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
        MainScreen(onStartFootActivity = {}) // 빈 람다 넘겨줌
    }
}