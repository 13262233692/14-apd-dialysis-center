package com.apd.dialysis.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeritonitisAlert {

    private String alertId;
    private Instant timestamp;
    private TurbidityReading.AlertLevel severity;
    private String title;
    private String description;
    private List<String> evidence;
    private double anomalyScore;
    private double transmittanceDropPercent;
    private double drainTimeExtensionPercent;
    private boolean requiresConfirmation;
    private boolean acknowledged;
    private List<TurbidityReading> spectralHistory72h;
    private List<Double> drainFlowHistory;
    private String recommendedAction;

    public static PeritonitisAlert critical(String alertId, TurbidityReading trigger,
                                            double transDropPct, double drainExtPct,
                                            List<TurbidityReading> history72h,
                                            List<Double> flowHistory) {
        List<String> evidence = new ArrayList<>();
        evidence.add(String.format("引流液透光率断崖式下跌 %.1f%% (当前 %.1f%%)",
                transDropPct, trigger.getTransmittancePercent()));
        evidence.add(String.format("引流时间异常延长 %.1f%% (已用时 %.1f min)",
                drainExtPct, trigger.getDrainElapsedMinutes()));
        evidence.add("光学吸收光谱 (420/540/660/720nm) 呈现白细胞+纤维蛋白聚集特征曲线");
        evidence.add("孤立森林无监督异常评分: " + String.format("%.3f", trigger.getAnomalyScore()));

        return PeritonitisAlert.builder()
                .alertId(alertId)
                .timestamp(Instant.now())
                .severity(TurbidityReading.AlertLevel.CRITICAL)
                .title("腹膜炎可疑 · 立即停机确诊")
                .description("检测到引流液浑浊度异常增高，高度提示细菌性腹膜炎早期表现。系统已自动触发最高级别警报，建议立即停止治疗并送检引流液。")
                .evidence(evidence)
                .anomalyScore(trigger.getAnomalyScore())
                .transmittanceDropPercent(transDropPct)
                .drainTimeExtensionPercent(drainExtPct)
                .requiresConfirmation(true)
                .acknowledged(false)
                .spectralHistory72h(history72h != null ? history72h : new ArrayList<>())
                .drainFlowHistory(flowHistory != null ? flowHistory : new ArrayList<>())
                .recommendedAction("1. 立即按下紧急停机按钮 2. 采集引流液样本送检 3. 联系主治医生 4. 记录浑浊度开始时间")
                .build();
    }
}
