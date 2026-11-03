package com.hengce.aps.admin.config;

import com.hengce.aps.solver.api.SolverAdapter;
import com.hengce.aps.solver.ortools.OrtoolsSolverAdapter;
import com.hengce.aps.solver.timefold.TimefoldSolverAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 求解器装配：组合根在此选择启用的引擎实现，业务层只看到 SolverAdapter。
 */
@Configuration
public class SolverConfiguration {

    /**
     * Timefold 引擎适配器。
     *
     * @return 适配器
     */
    @Bean
    public SolverAdapter timefoldSolverAdapter() {
        return new TimefoldSolverAdapter();
    }

    /**
     * OR-Tools 引擎适配器（预留）。
     *
     * @return 适配器
     */
    @Bean
    public SolverAdapter ortoolsSolverAdapter() {
        return new OrtoolsSolverAdapter();
    }
}
