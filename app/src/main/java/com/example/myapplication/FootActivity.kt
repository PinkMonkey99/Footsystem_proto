package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
//import androidx.compose.material3.Scaffold
//import androidx.compose.material3.CenterAlignedTopAppBar
//import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

// ⚙️ FootActivity: 족압 측정 화면을 보여 주는 Activity
class FootActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                // 전체 화면을 감싸는 Box
                Box(modifier = Modifier.fillMaxSize()) {
                    // ✅ 1. 왼쪽 상단에 뒤로 가기 버튼 배치
                    IconButton(
                        onClick = { finish() }, // 액티 비티 종료 = 이전 화면 으로 돌아감
                        modifier = Modifier
                            .padding(start = 16.dp, top = 48.dp)
                            .align(Alignment.TopStart) // 왼쪽 위 정렬
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로 가기"
                        )
                    }
                    // ✅ 2. 센서 및 발 이미지 화면
                    FootPressureScreen()
                }
            }
        }
    }
}
// 🦶 발 이미지 + 센서 5개를 화면에 보여 주는 UI 함수
@Composable
fun FootPressureScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center // 전체를 가운데 정렬
    ) {
        // 1. 발 이미지 표시
        Image(
            painter = painterResource(id = R.drawable.foot), // res/drawable/foot.png
            contentDescription = "Foot Image",                // 접근성 설명용
            contentScale = ContentScale.Fit,                  // 이미지 사이즈 맞추기
            modifier = Modifier.size(450.dp)                  // 이미지 크기
        )
        // 2. 센서 위치에 색상 동그 라미 표시
        // 각각 Box()로 하나씩 만들고, offset 으로 위치 조절
        // 센서 1 - 빨간색 (왼쪽 아래)
        Box(
            modifier = Modifier
                .offset(x = (-55).dp, y = (-30).dp)            // 위치 조정
                .size(30.dp)                                   // 크기
                .background(Color.Red, shape = CircleShape)    // 색상 + 동그 라미
        )
        // 센서 2 - 초록색 (중간 위)
        Box(
            modifier = Modifier
                .offset(x = (-15).dp, y = (-60).dp)
                .size(30.dp)
                .background(Color.Green, shape = CircleShape)
        )
        // 센서 3 - 노란색 (오른쪽 중간)
        Box(
            modifier = Modifier
                .offset(x = (35).dp, y = (-55).dp)
                .size(30.dp)
                .background(Color.Yellow, shape = CircleShape)
        )
        // 센서 4 - 파란색 (왼쪽 위)
        Box(
            modifier = Modifier
                .offset(x = (-25).dp, y = (45).dp)
                .size(30.dp)
                .background(Color.Blue, shape = CircleShape)
        )
        // 센서 5 - 분홍색 (오른쪽 아래)
        Box(
            modifier = Modifier
                .offset(x = (-2).dp, y = (130).dp)
                .size(30.dp)
                .background(Color.Magenta, shape = CircleShape)
        )
    }
}