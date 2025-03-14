<template>
  <div class="smyles_editor_wrap" :style="wrapStyle">
    <template v-if="!isAbridge">
      <editor class="smyles_editor" v-model="editorData" ref="kylinEditor" :style="{height: editorStyle.height}" :lang="lang" :theme="theme" @change="changeInput" @input="changeInput"></editor>
    </template>
    <template v-else>
      <editor class="smyles_editor" v-model="formatData" ref="kylinEditor" :style="{height: editorStyle.height}" :lang="lang" :theme="theme" @change="changeInput" @input="changeInput"></editor>
      <div class="limit-sql-tip" v-if="showLimitTip">{{needFormater ? $t('kylinLang.common.sqlPartLimitTip') : $t('kylinLang.common.sqlLimitTip')}}</div>
    </template>
    <div class="smyles_dragbar" v-if="dragable" v-drag:change.height="editorDragData"></div>
    <el-tooltip placement="top" :disabled="isFormat==='origin'">
      <div slot="content">{{$t('kylinLang.common.notice')}}<br/>
        <span class="tooltips-cont"><i class="el-ksd-n-icon-warning-filled ksd-mb-2"></i>
        <span>{{$t('kylinLang.common.formatTips')}}</span></span>
      </div>
      <el-button
        size="mini"
        @click.stop
        v-if="readOnly"
        icon-button
        icon="el-ksd-icon-dup_16"
        class="edit-copy-btn"
        :class="{'is-show': editorData, 'alwaysShow': alwaysShowCopyBtn}"
        v-clipboard:copy="fullFormatData || editorData"
        v-clipboard:success="onCopy"
        v-clipboard:error="onError"></el-button>
    </el-tooltip>
    <el-tabs v-model="isFormat" v-if="isFormatSwitch" class="format-switch" type="button" :class="{'en-model': $lang==='en'}" @tab-click="changeFormatType">
      <el-tab-pane :label="$t('kylinLang.common.origin')" name="origin"></el-tab-pane>
      <el-tab-pane :label="$t('kylinLang.common.format')" name="format"></el-tab-pane>
    </el-tabs>
  </div>
</template>
<script>
// import { format } from 'sql-formatter'
import { sqlRowsLimit, sqlStrLenLimit, formatSQLConfig } from '../../config/index'
import { isIE } from '../../util'
import { mapState } from 'vuex'
import Vue from 'vue'
import { Component } from 'vue-property-decorator'
@Component({
  props: {
    height: {
      default: 0
    },
    lang: {
      default: ''
    },
    theme: {
      default: ''
    },
    value: {
      default: ''
    },
    width: {
      default: '100%'
    },
    dragable: {
      default: true
    },
    isFormatter: {
      default: false
    },
    isFormatSwitch: {
      default: false
    },
    readOnly: {
      default: false
    },
    isAbridge: {
      type: Boolean,
      default: false
    },
    placeholder: {
      default: ''
    },
    needFormater: {
      type: Boolean,
      default: false
    },
    alwaysShowCopyBtn: {
      type: Boolean,
      default: true
    },
    tipsHeight: {
      type: Number,
      default: 32
    }
  },
  computed: {
    ...mapState({
      systemLang: state => state.system.lang
    }),
    editorStyle: function () {
      return {
        height: this.editorDragData.height ? this.editorDragData.height + 'px' : '100%',
        width: this.editorDragData.width ? this.editorDragData.width : '100%'
      }
    },
    wrapStyle () {
      return {
        // height: this.isAbridge && this.showLimitTip ? 'auto' : this.editorStyle.height,
        height: this.isAbridge && this.showLimitTip ? this.editorStyle.height + this.tipsHeight : this.editorStyle.height + 2,
        width: this.editorStyle.width
      }
    }
  },
  methods: {
    editorResize () {
      this.editorDragData.height = +this.height || 0
    },
    changeInput () {
      this.updateEditor(this.$refs.kylinEditor.editor)
      this.$emit('input', this.editorData)
    },
    setOption (option) {
      var editor = this.$refs.kylinEditor.editor
      editor.setOptions(Object.assign({
        wrap: 'free',
        enableBasicAutocompletion: true,
        enableSnippets: true,
        enableLiveAutocompletion: true
      }, option))
    },
    getValue () {
      var editor = this.$refs.kylinEditor.editor
      return editor.getValue()
    },
    onCopy () {
      if (navigator.userAgent.indexOf('Windows NT') >= 0 && window.clipboardData) {
        let text = window.clipboardData.getData('text')
        if (text && text === this.editorData) {
          this.$message({
            type: 'success',
            message: this.$t('kylinLang.common.copySuccess')
          })
        } else {
          this.$message(this.$t('kylinLang.common.copyfail'))
        }
      } else {
        this.$message({
          type: 'success',
          message: this.$t('kylinLang.common.copySuccess')
        })
      }
    },
    onError () {
      this.$message(this.$t('kylinLang.common.copyfail'))
    },
    // 截取前100行sql
    abridgeData (format) {
      const splitData = this.editorData.split('\n')
      // 需要截断的默认都是已经格式化后的，如果传入需要格式化，就再手动格式化，且格式化方式是通过字符串长度判断
      if (!isIE() && (this.needFormater || format) && (splitData.length === 1 || (splitData.length === 2 && /^LIMIT (\d+)/.test(splitData[1])))) {
        const data = this.editorData.length > sqlStrLenLimit ? `${this.editorData.slice(0, sqlStrLenLimit)}...` : this.editorData
        // 是否显示 tips 取决于填入的 sql 字符数是否超过全局配置的
        this.showLimitTip = this.editorData.length > sqlStrLenLimit
        this.formatData = this.handleFormatSql(data, formatSQLConfig)
        this.fullFormatData = this.handleFormatSql(this.editorData, formatSQLConfig)
      } else {
        const data = this.editorData.split('\n')
        // 是否显示 tips 取决于填入的 sql 行数是否超过全局配置的
        this.showLimitTip = data.length > sqlRowsLimit
        this.formatData = data.length > sqlRowsLimit ? data.slice(0, sqlRowsLimit).join('\n') + '...' : this.editorData
        this.fullFormatData = this.editorData
      }
    },
    changeFormatType (type) {
      this.isAbridge && this.abridgeData(type.name === 'format')
    },
    handleFormatSql (sql) {
      try {
        const fsql = this._formatSql(sql, formatSQLConfig)
        return fsql ?? sql
      } catch (e) {
        console.warn('The browser version is too low, please update the version.')
        return sql
      }
    },
    getAbridgeType () {
      this.isAbridge && this.abridgeData()
    },
    updateEditor (editor) {
      if (this.placeholder) {
        let shouldShow = !editor.session.getValue().length
        let node = editor.renderer.emptyMessageNode
        if (!shouldShow && node) {
          editor.renderer.scroller.removeChild(editor.renderer.emptyMessageNode)
          editor.renderer.emptyMessageNode = null
        } else if (shouldShow && !node) {
          node = document.createElement('div')
          editor.renderer.emptyMessageNode = node
          node.innerHTML = this.placeholder
          node.className = 'ace_invisible ace_emptyMessage'
          node.style.padding = '0 5px'
          node.style.position = 'absolute'
          node.style.zIndex = 5
          editor.renderer.scroller.appendChild(node)
        }
      }
    }
  },
  watch: {
    value (val) {
      this.editorData = val
      this.getAbridgeType()
    },
    readOnly (val) {
      if (this.$refs.kylinEditor.editor) {
        this.$refs.kylinEditor.editor.setReadOnly(val)
      }
    },
    'editorDragData.height' (val) {
      if (val) {
        var editor = this.$refs.kylinEditor.editor
        editor.resize()
      }
    },
    systemLang () {
      this.isAbridge && this.abridgeData()
    }
  }
})
export default class KapEditor extends Vue {
  data () {
    return {
      editorData: this.value,
      formatData: '',
      fullFormatData: '',
      dragging: false,
      editorDragData: {
        height: +this.height || 0,
        width: this.width
      },
      showLimitTip: false,
      isFormat: 'origin'
    }
  }
  mounted () {
    var editor = this.$refs.kylinEditor.editor
    // editor.setOption('wrap', 'free')
    // var editorWrap = this.$el
    // var smylesEditor = this.$el.querySelector('.smyles_editor')
    this.updateEditor(editor)
    if (this.readOnly) {
      editor.setReadOnly(this.readOnly)
    }
    this.$on('setReadOnly', (isReadyOnly) => {
      editor.setReadOnly(isReadyOnly)
    })
    this.setOption()
    this.$on('setOption', (option) => {
      this.setOption(option)
    })
    this.$on('focus', () => {
      editor.focus()
    })
    this.$on('insert', (val) => {
      editor.insert(val)
    })
    this.$on('setValue', (val) => {
      editor.setValue(val)
    })
    this.$on('setAutoCompleteData', (autoCompleteData) => {
      editor.completers.splice(0, editor.completers.length - 3)
      editor.completers.unshift({
        identifierRegexps: [/[.a-zA-Z_0-9]/],
        getCompletions (editor, session, pos, prefix, callback) {
          setTimeout(() => {
            const { filtered } = editor.completer.completions
            const dataList = filtered.map(it => it.value)
            const list = autoCompleteData.filter(it => !dataList.includes(it.value))
            if (prefix.length === 0) {
              return callback(null, list)
            } else {
              return callback(null, list)
            }
          }, 0)
        }
      })
      editor.commands.on('afterExec', function (e, t) {
        if (e.command.name === 'insertstring' && (e.args === ' ' || e.args === '.')) {
          var all = e.editor.completers
          // e.editor.completers = completers;
          e.editor.execCommand('startAutocomplete')
          e.editor.completers = all
        }
      })
    })
    this.getAbridgeType()
  }
}
</script>
<style lang="less">
  @import '../../assets/styles/variables.less';
  .smyles_editor_wrap {
    width: 100%;
    position: relative;
    border: 1px solid @ke-border-secondary;
    box-sizing: border-box;
    // background-color: @aceditor-bg-color;
    border-radius: 6px;
    .format-switch {
      position: absolute;
      bottom: -5px;
      right: 10px;
      z-index: 1;
      .el-tabs__header {
        margin: 0;
      }
    }
    .ace_print-margin {
      visibility: hidden !important;
    }
    .smyles_editor {
      // width: calc(~'100% - 50px') !important;
      border: none;
      border-radius: 6px;
    }
    .smyles_dragbar {
      width: 100%;
      border-bottom: 1px solid @ke-border-secondary;
      cursor: row-resize;
      opacity: 1;
      position: relative;
      bottom: 0px;
      &:hover {
        border-color: @base-color;
      }
    }
    .edit-copy-btn {
      position: absolute;
      right: 16px;
      top: 8px;
      z-index: 9;
      opacity: 0;
      display: none;
      cursor: pointer;
      // background-color: rgba(255,255,255,0.2);
      &.alwaysShow{
        display: block;
        opacity: 1;
      }
      &.is-show {
        display: block;
      }
      &:hover {
        color: @base-color;
      }
    }
    &:hover {
      .edit-copy-btn {
        opacity: 1;
      }
    }
    .el-popover {
      right: 16px;
      top: 0px;
      min-width: 80px;
      text-align: right;
      background-color: transparent;
      border-color: transparent;
      box-shadow: none;
      .el-icon-circle-check {
        color: @normal-color-1;
      }
    }
    .limit-sql-tip {
      width: calc(~'100% + 2px');
      /* height: 30px;
      line-height: 30px; */
      text-align: center;
      font-size: 12px;
      background: @fff;
      color: @text-normal-color;
      border: 1px solid @ke-border-secondary;
      border-bottom: none;
      box-sizing: border-box;
      margin-left: -1px;
      padding:5px 0;
      line-height: 1.8;
    }
    .ace-chrome {
      .ace_marker-layer .ace_active-line {
        background: @ke-color-info-secondary-bg;
      }
      .ace_gutter-active-line {
        background: @ke-color-info-secondary-bg;
      }
      .ace_gutter {
        background: @ke-color-info-secondary-bg;
      }
    }
  }
  .tooltips-cont {
    color: @text-disabled-color;
  }
</style>
