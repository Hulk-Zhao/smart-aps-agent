import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import http from './api/http'
import App from './App.vue'
import { installMockIfEnabled } from './mocks'
import router from './router'
import './styles/main.css'

installMockIfEnabled(http)

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
