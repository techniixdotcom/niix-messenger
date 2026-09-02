package app.niix.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.niix.AppContainer
import app.niix.CalculatorMemory
import app.niix.NiixApp
import app.niix.R
import app.niix.TempFileGuard
import app.niix.UnlockFlow
import kotlinx.coroutines.launch

class CalculatorActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private val expr = StringBuilder()
    private var justEvaluated = false

    private val container: AppContainer get() = (application as NiixApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)
        display = findViewById(R.id.calc_display)
        val grid = findViewById<GridLayout>(R.id.calc_grid)
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is Button) child.setOnClickListener { onKey(child.text.toString()) }
        }
        render()
    }

    override fun onResume() {
        super.onResume()

        container.storage.appLock.lock()
        TempFileGuard.purge(this)
        expr.clear()
        justEvaluated = false
        render()
    }

    private fun onKey(key: String) {
        when (key) {
            "AC" -> { expr.clear(); justEvaluated = false; render() }
            "⌫" -> { if (expr.isNotEmpty()) expr.deleteCharAt(expr.length - 1); render() }
            "±" -> { toggleSign(); render() }
            "%" -> { percent(); render() }
            "=" -> onEquals()
            "MC" -> CalculatorMemory.clear(this)
            "MR" -> {
                CalculatorMemory.recall(this)?.let { memory ->
                    if (justEvaluated) { expr.clear(); justEvaluated = false }
                    expr.append(memory)
                    render()
                }
            }
            "M+" -> {
                currentNumericValue()?.let { value ->
                    val previous = CalculatorMemory.recall(this)?.toDoubleOrNull() ?: 0.0
                    CalculatorMemory.store(this, formatNumber(previous + value))
                }
            }
            "MS" -> currentNumericValue()?.let { CalculatorMemory.store(this, formatNumber(it)) }
            "+", "−", "×", "÷" -> { justEvaluated = false; expr.append(key); render() }
            "." -> { if (justEvaluated) { expr.clear(); justEvaluated = false }; expr.append("."); render() }
            else -> { if (justEvaluated) { expr.clear(); justEvaluated = false }; expr.append(key); render() }
        }
    }

    private fun render() {
        display.text = if (expr.isEmpty()) "0" else expr.toString()
    }

    private fun currentNumericValue(): Double? {
        display.text.toString().toDoubleOrNull()?.let { return it }
        val evaluated = evaluate(expr.toString())
        return if (evaluated != "Error") evaluated.toDoubleOrNull() else null
    }

    private fun onEquals() {
        val raw = expr.toString()

        val result = evaluate(raw)
        display.text = if (result.isEmpty()) "0" else result
        expr.clear()
        if (result != "Error") expr.append(result)
        justEvaluated = true

        if (!container.storage.appLock.isSetUp()) {

            if (raw == REGISTRATION_CODE) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
            return
        }

        if (raw.matches(Regex("^\\d{6,}$"))) tryUnlock(raw)
    }

    private fun tryUnlock(raw: String) {
        lifecycleScope.launch {
            val opened = UnlockFlow.attempt(container, raw)
            if (opened) {
                expr.clear()
                render()
                startActivity(Intent(this@CalculatorActivity, HomeActivity::class.java))
                finish()
            }
        }
    }

    private fun toggleSign() {
        if (expr.isNotEmpty() && expr[0] == '-') expr.deleteCharAt(0) else expr.insert(0, '-')
    }

    private fun percent() {
        val value = evaluate(expr.toString())
        val d = value.toDoubleOrNull()
        if (value != "Error" && d != null) {
            expr.clear()
            expr.append(formatNumber(d / 100.0))
            justEvaluated = true
        }
    }

    private fun evaluate(input: String): String {
        val normalized = input.replace('×', '*').replace('÷', '/').replace('−', '-')
        if (normalized.isEmpty()) return "0"
        val tokens = ArrayList<Any>()
        var num = StringBuilder()
        for (ch in normalized) {
            when {
                ch.isDigit() || ch == '.' -> num.append(ch)
                ch == '+' || ch == '-' || ch == '*' || ch == '/' -> {
                    if (num.isEmpty() && ch == '-' && (tokens.isEmpty() || tokens.last() is Char)) {
                        num.append('-')
                    } else {
                        val d = num.toString().toDoubleOrNull() ?: return "Error"
                        tokens.add(d); num = StringBuilder(); tokens.add(ch)
                    }
                }
                else -> return "Error"
            }
        }
        val lastD = num.toString().toDoubleOrNull() ?: return "Error"
        tokens.add(lastD)

        val p = ArrayList<Any>()
        p.add(tokens[0])
        var i = 1
        while (i < tokens.size) {
            val op = tokens[i] as Char
            val v = tokens[i + 1] as Double
            when (op) {
                '*' -> p[p.size - 1] = (p[p.size - 1] as Double) * v
                '/' -> { if (v == 0.0) return "Error"; p[p.size - 1] = (p[p.size - 1] as Double) / v }
                else -> { p.add(op); p.add(v) }
            }
            i += 2
        }
        var res = p[0] as Double
        i = 1
        while (i < p.size) {
            val op = p[i] as Char
            val v = p[i + 1] as Double
            res = if (op == '+') res + v else res - v
            i += 2
        }
        return formatNumber(res)
    }

    private fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "Error"
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            String.format("%.10f", d).trimEnd('0').trimEnd('.')
        }
    }

    companion object {

        private const val REGISTRATION_CODE = "1+6+1"
    }
}
